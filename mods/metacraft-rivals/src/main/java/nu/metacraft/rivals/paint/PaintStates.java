package nu.metacraft.rivals.paint;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The client-state table (spec §2). A vanilla client can only be shown vanilla blockstates, and a
 * painted cell now needs to say which of its four in-plane neighbours are painted, so paint borrows
 * every state of four donor blocks that render whatever the pack says, have no collision, emit no
 * light and have no client-side behaviour: the two multiface blocks (63 usable states each: not
 * waterlogged, at least one face), tripwire (128) and redstone wire at power 0 (81; its
 * {@code animateTick} only spawns dust when powered). Glow lichen is deliberately not a donor:
 * {@code GlowLichenBlock.emission} gives light 7 to every state with a face. Server states are
 * numbered per colour — connected first (face × bits, 96), then the multi-face splat masks (57) —
 * and take the pool in donor order.
 */
public final class PaintStates {
	public static final List<Block> DONORS = List.of(Blocks.SCULK_VEIN, Blocks.RESIN_CLUMP, Blocks.TRIPWIRE, Blocks.REDSTONE_WIRE);
	public static final int CONNECTED_PER_COLOR = 6 * 16;
	public static final int SPLAT_PER_COLOR = 63 - 6;
	private static final int PER_COLOR = CONNECTED_PER_COLOR + SPLAT_PER_COLOR;
	private static final Direction[] DIRECTIONS = Direction.values();
	private static final List<BlockState> POOL = pool();

	private PaintStates() {}

	/** The pool: every usable donor state, in donor order, each donor's states in registry order. */
	private static List<BlockState> pool() {
		List<BlockState> out = new ArrayList<>();
		for (Block donor : DONORS) {
			for (BlockState state : donor.getStateDefinition().getPossibleStates()) {
				if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) continue;
				if (donor instanceof MultifaceBlock) {
					boolean any = false;
					for (Direction d : DIRECTIONS) any |= state.getValue(MultifaceBlock.getFaceProperty(d));
					if (!any) continue;
				}
				if (donor == Blocks.REDSTONE_WIRE && state.getValue(RedStoneWireBlock.POWER) != 0) continue;
				out.add(state);
			}
		}
		if (out.size() < PaintColor.values().length * PER_COLOR) {
			throw new IllegalStateException("[" + Rivals.MOD_ID + "] " + out.size() + " donor states for "
					+ PaintColor.values().length * PER_COLOR + " paint states");
		}
		return List.copyOf(out);
	}

	public static BlockState connected(PaintColor color, Direction face, int bits) {
		return POOL.get(color.ordinal() * PER_COLOR + face.ordinal() * 16 + (bits & 15));
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
		return POOL.get(color.ordinal() * PER_COLOR + CONNECTED_PER_COLOR + index);
	}

	/** Every client state in use, for tests and the pack. */
	public static List<BlockState> all() {
		return List.copyOf(POOL.subList(0, PaintColor.values().length * PER_COLOR));
	}

	/** Which server state a client state stands for, for the pack: (colour, face, bits) or (colour, mask). */
	public record Entry(PaintColor color, @Nullable Direction face, int bits, int faceMask) {}

	public static Entry entry(BlockState client) {
		int i = POOL.indexOf(client);
		if (i < 0 || i >= PaintColor.values().length * PER_COLOR) throw new IllegalArgumentException("not a paint state: " + client);
		PaintColor color = PaintColor.values()[i / PER_COLOR];
		int local = i % PER_COLOR;
		if (local < CONNECTED_PER_COLOR) return new Entry(color, DIRECTIONS[local / 16], local % 16, 1 << (local / 16));
		int index = local - CONNECTED_PER_COLOR;
		for (int mask = 1; mask < 64; mask++) {
			if (Integer.bitCount(mask) < 2) continue;
			if (index-- == 0) return new Entry(color, null, 0, mask);
		}
		throw new IllegalStateException();
	}
}
