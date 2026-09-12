package nu.metacraft.rivals.paint;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client-state table (spec §2). A vanilla client can only be shown vanilla blockstates, and a
 * painted cell now needs to say which of its four in-plane neighbours are painted, so paint borrows
 * every state of four donor blocks that render whatever the pack says, have no collision, emit no
 * light and have no client-side behaviour: the two multiface blocks (63 usable states each: not
 * waterlogged, at least one face), tripwire (128) and redstone wire at power 0 (81; its
 * {@code animateTick} only spawns dust when powered). Glow lichen is deliberately not a donor:
 * {@code GlowLichenBlock.emission} gives light 7 to every state with a face.
 *
 * <p><b>Which donor state stands for which paint state is chosen by shape, not by counting.</b> The
 * pack can repaint a borrowed state but it cannot change the state's outline, and the client draws
 * the targeted-block highlight from the <em>client</em> state's shape. Handing paint out by
 * contiguous slices put floor cells on sculk-vein states whose face flags drew slabs on random faces
 * and wall cells on a tripwire square lying at the floor, so the outline box floated nowhere near the
 * ink. The allocator below spends the budget (306 of 335 usable states) on matching outlines instead:
 *
 * <ol>
 * <li><b>Splat cells are exact.</b> A multiface donor's shape is the union of 1-px slabs on its set
 *     faces, so a splat mask maps to the state whose six face flags <em>are</em> that mask: DATA on
 *     sculk vein, IT on resin clump. 57 masks per colour, outline exactly the painted faces.</li>
 * <li><b>Floor cells are flat.</b> Attach {@link Direction#DOWN} takes {@code FLAT}: tripwire
 *     {@code attached=true}, a full-square 2.5-px-thin slab lying on the floor.</li>
 * <li><b>Wall cells are striped.</b> Attach N/E/S/W prefers a redstone-wire state whose side on that
 *     direction is {@code up}, which adds a full-height 1-px strip climbing that face of the cell —
 *     the nearest thing any donor has to a wall decal. Each strip state is spent once, fewest other
 *     {@code up} sides first and on whichever of its own {@code up} directions is shortest, which
 *     spreads the 65 of them sixteen to a direction: eight per colour. The other eight per colour
 *     fall back to the leftover FLAT states and then to HALF.</li>
 * <li><b>Ceiling cells are halves.</b> Attach {@link Direction#UP} takes {@code HALF}: tripwire
 *     {@code attached=false}, a half box. No donor draws a ceiling slab with 16 spare states, and
 *     ceilings are the rarest cell there is.</li>
 * </ol>
 *
 * <p>The allocation runs once, in a fixed order, and throws rather than reusing a state or running a
 * pool dry. {@link #entry()} is the reverse map.
 */
public final class PaintStates {
	public static final List<Block> DONORS = List.of(Blocks.SCULK_VEIN, Blocks.RESIN_CLUMP, Blocks.TRIPWIRE, Blocks.REDSTONE_WIRE);
	public static final int CONNECTED_PER_COLOR = 6 * 16;
	public static final int SPLAT_PER_COLOR = 63 - 6;
	private static final int PER_COLOR = CONNECTED_PER_COLOR + SPLAT_PER_COLOR;
	private static final Direction[] DIRECTIONS = Direction.values();
	/** The multiface donor each colour's splat masks come from, in colour order. */
	private static final List<Block> EXACT_DONORS = List.of(Blocks.SCULK_VEIN, Blocks.RESIN_CLUMP);

	/** The table, indexed {@code colour * PER_COLOR + local} exactly as {@link #entry} decodes it. */
	private static final List<BlockState> TABLE = table();
	private static final Map<BlockState, Entry> ENTRIES = entries();

	private PaintStates() {}

	// ---------------------------------------------------------------- the pools

	/** Every usable state of {@code donor}, in registry order: never waterlogged, never lit, never powered wire. */
	private static List<BlockState> usable(Block donor) {
		List<BlockState> out = new ArrayList<>();
		for (BlockState state : donor.getStateDefinition().getPossibleStates()) {
			if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) continue;
			if (donor instanceof MultifaceBlock && faceMask(state) == 0) continue;
			if (donor == Blocks.REDSTONE_WIRE && state.getValue(RedStoneWireBlock.POWER) != 0) continue;
			out.add(state);
		}
		return out;
	}

	/** A multiface donor state's six face booleans, packed the same way paint packs its own. */
	private static int faceMask(BlockState state) {
		int mask = 0;
		for (Direction d : DIRECTIONS) {
			if (state.getValue(MultifaceBlock.getFaceProperty(d))) mask |= 1 << d.ordinal();
		}
		return mask;
	}

	/** How many of a redstone-wire state's four sides climb the wall. */
	private static int ups(BlockState wire) {
		int ups = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (wire.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(d)) == RedstoneSide.UP) ups++;
		}
		return ups;
	}

	/**
	 * Redstone-wire strip states per wall direction. A state is spent on one direction only, so the 65
	 * of them have to be spread: they are handed out fewest-{@code up}-sides first (every extra
	 * {@code up} is a strip climbing a face the paint is not on), and each one goes to whichever of its
	 * own {@code up} directions is currently shortest, ties in N/E/S/W order. Taking each state's first
	 * {@code up} side instead would give NORTH 27 and WEST 8; balancing gives every direction its
	 * sixteen, which is exactly eight per colour.
	 */
	private static Map<Direction, Deque<BlockState>> strips() {
		Map<Direction, List<BlockState>> sorted = new EnumMap<>(Direction.class);
		for (Direction d : Direction.Plane.HORIZONTAL) sorted.put(d, new ArrayList<>());
		List<BlockState> wires = new ArrayList<>();
		for (BlockState wire : usable(Blocks.REDSTONE_WIRE)) {
			if (ups(wire) > 0) wires.add(wire);
		}
		wires.sort(Comparator.comparingInt(PaintStates::ups));
		for (BlockState wire : wires) {
			Direction shortest = null;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				if (wire.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(d)) != RedstoneSide.UP) continue;
				if (shortest == null || sorted.get(d).size() < sorted.get(shortest).size()) shortest = d;
			}
			sorted.get(shortest).add(wire);
		}
		Map<Direction, Deque<BlockState>> pools = new EnumMap<>(Direction.class);
		sorted.forEach((d, states) -> pools.put(d, new ArrayDeque<>(states)));
		return pools;
	}

	/** Flat-on-the-floor states: tripwire {@code attached=true} first, then the redstone wires with no {@code up}. */
	private static Deque<BlockState> flats() {
		Deque<BlockState> out = new ArrayDeque<>();
		for (BlockState state : usable(Blocks.TRIPWIRE)) {
			if (state.getValue(TripWireBlock.ATTACHED)) out.add(state);
		}
		for (BlockState wire : usable(Blocks.REDSTONE_WIRE)) {
			if (ups(wire) == 0) out.add(wire);
		}
		return out;
	}

	/** Half-box states: tripwire {@code attached=false}. */
	private static Deque<BlockState> halves() {
		Deque<BlockState> out = new ArrayDeque<>();
		for (BlockState state : usable(Blocks.TRIPWIRE)) {
			if (!state.getValue(TripWireBlock.ATTACHED)) out.add(state);
		}
		return out;
	}

	// ---------------------------------------------------------------- the allocator

	private static List<BlockState> table() {
		int colors = PaintColor.values().length;
		if (colors > EXACT_DONORS.size()) {
			throw new IllegalStateException("[" + Rivals.MOD_ID + "] " + colors + " colours but "
					+ EXACT_DONORS.size() + " multiface donors to give each one its own exact splats");
		}
		BlockState[] table = new BlockState[colors * PER_COLOR];
		// 1. Splats: the multiface state whose face flags are the mask, so the outline is the ink.
		for (PaintColor color : PaintColor.values()) {
			Map<Integer, BlockState> byMask = new HashMap<>();
			for (BlockState state : usable(EXACT_DONORS.get(color.ordinal()))) byMask.put(faceMask(state), state);
			int index = 0;
			for (int mask = 1; mask < 64; mask++) {
				if (Integer.bitCount(mask) < 2) continue;
				BlockState exact = byMask.get(mask);
				if (exact == null) throw new IllegalStateException("[" + Rivals.MOD_ID + "] no exact donor state for mask " + mask);
				table[color.ordinal() * PER_COLOR + CONNECTED_PER_COLOR + index++] = exact;
			}
		}
		Deque<BlockState> flat = flats();
		Deque<BlockState> half = halves();
		Map<Direction, Deque<BlockState>> strips = strips();
		// 2. Floors are flat, 3. walls are striped (falling back to flat then half), 4. ceilings are halves.
		// Walls run before ceilings so their fallback eats the flat leftovers first and the halves last.
		fill(table, Direction.DOWN, flat);
		for (Direction wall : Direction.Plane.HORIZONTAL) fill(table, wall, strips.get(wall), flat, half);
		fill(table, Direction.UP, half);
		List<BlockState> out = List.of(table);
		if (out.size() != Set.copyOf(out).size()) throw new IllegalStateException("[" + Rivals.MOD_ID + "] a donor state was handed out twice");
		return out;
	}

	/**
	 * Every colour's sixteen bit patterns for one attach face, from the first pool with anything left.
	 * The colours are interleaved rather than filled one after the other: a pool that runs short part
	 * way through — the strip states do, on every wall direction — then shorts both teams by the same
	 * amount instead of giving one team every strip and the other none.
	 */
	@SafeVarargs
	private static void fill(BlockState[] table, Direction face, Deque<BlockState>... pools) {
		for (int bits = 0; bits < 16; bits++) {
			for (PaintColor color : PaintColor.values()) {
				table[color.ordinal() * PER_COLOR + face.ordinal() * 16 + bits] = take(color, face, pools);
			}
		}
	}

	@SafeVarargs
	private static BlockState take(PaintColor color, Direction face, Deque<BlockState>... pools) {
		for (Deque<BlockState> pool : pools) {
			BlockState state = pool.poll();
			if (state != null) return state;
		}
		throw new IllegalStateException("[" + Rivals.MOD_ID + "] out of donor states for " + color + " " + face);
	}

	private static Map<BlockState, Entry> entries() {
		Map<BlockState, Entry> out = new HashMap<>();
		for (int i = 0; i < TABLE.size(); i++) {
			PaintColor color = PaintColor.values()[i / PER_COLOR];
			int local = i % PER_COLOR;
			out.put(TABLE.get(i), local < CONNECTED_PER_COLOR
					? new Entry(color, DIRECTIONS[local / 16], local % 16, 1 << (local / 16))
					: new Entry(color, null, 0, splatMask(local - CONNECTED_PER_COLOR)));
		}
		return Map.copyOf(out);
	}

	/** The {@code index}-th face mask with at least two bits, counting up from 0. */
	private static int splatMask(int index) {
		for (int mask = 1; mask < 64; mask++) {
			if (Integer.bitCount(mask) < 2) continue;
			if (index-- == 0) return mask;
		}
		throw new IllegalArgumentException("no splat mask " + index);
	}

	// ---------------------------------------------------------------- the lookups

	public static BlockState connected(PaintColor color, Direction face, int bits) {
		return TABLE.get(color.ordinal() * PER_COLOR + face.ordinal() * 16 + (bits & 15));
	}

	/** {@code faceMask} bit i = Direction i painted. One face is a connected state with no bits. */
	public static BlockState splat(PaintColor color, int faceMask) {
		int popcount = Integer.bitCount(faceMask & 63);
		if (popcount == 0) throw new IllegalArgumentException("empty face mask");
		if (popcount == 1) return connected(color, DIRECTIONS[Integer.numberOfTrailingZeros(faceMask)], 0);
		// Number the masks with ≥ 2 bits in increasing order: 0..56.
		int index = 0;
		for (int mask = 1; mask < 64; mask++) {
			if (Integer.bitCount(mask) < 2) continue;
			if (mask == (faceMask & 63)) break;
			index++;
		}
		return TABLE.get(color.ordinal() * PER_COLOR + CONNECTED_PER_COLOR + index);
	}

	/** Every client state in use, for tests and the pack. */
	public static List<BlockState> all() {
		return TABLE;
	}

	/** Which server state a client state stands for, for the pack: (colour, face, bits) or (colour, mask). */
	public record Entry(PaintColor color, @Nullable Direction face, int bits, int faceMask) {}

	public static Entry entry(BlockState client) {
		Entry entry = ENTRIES.get(client);
		if (entry == null) throw new IllegalArgumentException("not a paint state: " + client);
		return entry;
	}
}
