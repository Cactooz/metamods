package nu.metacraft.rivals.paint;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.pack.SplatArt;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Paint for faces a multiface block cannot sit on (stairs, slabs, fences, panes …): one flat splat quad
 * per outline-box face on the struck side, as Polymer item displays that wrap the block's real shape.
 * One holder per cell, one colour per cell; recolouring rebuilds the holder. In memory only, like the
 * tally: a restart drops them.
 *
 * <p>Quads are not blocks, so nothing tells them to fall: {@link #count} sweeps the cells and drops the
 * ones whose holder or surface is gone, the same way {@link PaintTally} sweeps its paint blocks.
 */
public final class PaintDisplays {
	private static final Map<ResourceKey<Level>, PaintDisplays> ALL = new HashMap<>();
	private static final double LIFT = 0.01;
	/**
	 * How many quads one cell may hold. A complex shape (a wall post plus four arms, a pane cross) can
	 * report a dozen outline boxes, and a quad per box is a dozen item displays in one cell for every
	 * client in range; three of them, the largest on the struck side, already read as a splat.
	 */
	static final int MAX_QUADS_PER_CELL = 3;

	/**
	 * A painted cell. {@code state} is the surface's block state at paint time: the quads are cut to
	 * that shape, so a surface that changes shape under them (a stair turned, a slab filled to a double
	 * slab) leaves them wrong and they are dropped rather than moved.
	 */
	private record Painted(PaintColor color, ElementHolder holder, int quads, BlockPos surface, Direction face, BlockState state) {}

	private final Map<BlockPos, Painted> cells = new HashMap<>();

	public static PaintDisplays of(ServerLevel level) {
		return ALL.computeIfAbsent(level.dimension(), key -> new PaintDisplays());
	}

	public static void clearAll() {
		ALL.values().forEach(PaintDisplays::clear);
		ALL.clear();
	}

	public int holders() {
		return cells.size();
	}

	public @Nullable PaintColor colorAt(BlockPos cell) {
		Painted painted = cells.get(cell);
		return painted == null ? null : painted.color;
	}

	/** The face the quads in {@code cell} are painted on, or null if there are none. */
	public @Nullable Direction faceAt(BlockPos cell) {
		Painted painted = cells.get(cell);
		return painted == null ? null : painted.face;
	}

	/**
	 * Quads per colour, counted as faces, dropping the cells whose paint is gone. Every colour has an entry.
	 */
	public Map<PaintColor, Integer> count(ServerLevel level) {
		Map<PaintColor, Integer> counts = new EnumMap<>(PaintColor.class);
		for (PaintColor color : PaintColor.values()) counts.put(color, 0);
		Iterator<Map.Entry<BlockPos, Painted>> it = cells.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<BlockPos, Painted> entry = it.next();
			Painted painted = entry.getValue();
			if (!alive(level, entry.getKey(), painted)) {
				painted.holder.destroy();
				it.remove();
				continue;
			}
			counts.merge(painted.color, painted.quads, Integer::sum);
		}
		return counts;
	}

	/**
	 * Whether this cell still holds real paint. Polymer destroys every attachment in a chunk when the chunk
	 * unloads, which nulls the holder's attachment and leaves the entry scoring for quads nobody can see; a
	 * broken or replaced surface leaves the quads hanging in the air; and a block built into the cell buries
	 * them. A full face is gone too: that side takes a paint block now, not quads. The surface must also be
	 * the very same state it was painted on — block states are interned, so reference equality is the test —
	 * because a shape change (a stair turned, a slab doubled) moves the faces out from under the quads.
	 */
	private boolean alive(ServerLevel level, BlockPos cell, Painted painted) {
		if (painted.holder.getAttachment() == null) return false;
		BlockState surface = level.getBlockState(painted.surface);
		if (surface != painted.state) return false;
		if (!Painter.paintable(surface)) return false;
		if (Block.isFaceFull(surface.getCollisionShape(level, painted.surface), painted.face)) return false;
		return free(level.getBlockState(cell));
	}

	/** A cell quads may live in: empty, or a paint block put there by a full face beside it. */
	private static boolean free(BlockState cell) {
		return cell.isAir() || cell.getBlock() instanceof PaintBlock;
	}

	/** Destroy every holder in this level. Returns how many cells were cleared. */
	public int clear() {
		int n = cells.size();
		cells.values().forEach(painted -> painted.holder.destroy());
		cells.clear();
		return n;
	}

	/**
	 * Cover the {@code face} side of every outline box of the block at {@code surface} with quads in the
	 * cell in front. Returns false when the cell already holds this colour, the cell is built up, or the
	 * shape has no boxes. A cell whose paint has died (unloaded chunk, surface gone) counts as empty and is
	 * rebuilt, so a team can always repaint its own colour.
	 */
	public boolean paint(ServerLevel level, BlockPos surface, Direction face, PaintColor color) {
		BlockPos cell = surface.relative(face).immutable();
		Painted existing = cells.get(cell);
		if (existing != null && !alive(level, cell, existing)) {
			existing.holder.destroy();
			cells.remove(cell);
			existing = null;
		}
		if (existing != null && existing.color == color) return false;
		if (!free(level.getBlockState(cell))) return false;
		BlockState state = level.getBlockState(surface);
		// The outline shape, not the collision shape: a fence's collision box is 1.5 blocks tall, and paint
		// on top of it would float half a block over the post.
		VoxelShape shape = state.getShape(level, surface);
		if (shape.isEmpty()) shape = state.getCollisionShape(level, surface);
		List<AABB> boxes = largestFaces(shape.toAabbs(), face);
		if (boxes.isEmpty()) return false;
		if (existing != null) existing.holder.destroy();
		ElementHolder holder = new ElementHolder();
		Vec3 origin = Vec3.atLowerCornerOf(cell);
		int quads = 0;
		for (AABB box : boxes) {
			holder.addElement(quad(box, surface, face, color, origin, level.getRandom().nextInt(SplatArt.SHAPES.length)));
			quads++;
		}
		ChunkAttachment.of(holder, level, origin);
		cells.put(cell, new Painted(color, holder, quads, surface.immutable(), face, state));
		return true;
	}

	/** At most {@link #MAX_QUADS_PER_CELL} boxes, the ones showing the most of themselves on {@code face}. */
	private static List<AABB> largestFaces(List<AABB> boxes, Direction face) {
		if (boxes.size() <= MAX_QUADS_PER_CELL) return boxes;
		List<AABB> sorted = new ArrayList<>(boxes);
		sorted.sort(Comparator.comparingDouble((AABB box) -> faceArea(box, face)).reversed());
		return sorted.subList(0, MAX_QUADS_PER_CELL);
	}

	/** The area of {@code box}'s {@code face} side, with the same in-plane axes {@link #quad} uses. */
	private static double faceArea(AABB box, Direction face) {
		double w = face.getAxis() == Direction.Axis.X ? box.getZsize() : box.getXsize();
		double h = face.getAxis() == Direction.Axis.Y ? box.getZsize() : box.getYsize();
		return w * h;
	}

	/** One splat quad on the {@code face} side of {@code box} (box coordinates are local to the surface block). */
	private static ItemDisplayElement quad(AABB box, BlockPos surface, Direction face, PaintColor color, Vec3 origin, int shape) {
		Vector3f n = new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ());
		// In-plane axes: u is the model's X, v the model's Y. v is the "up" of the quad — world +Z on the
		// horizontal faces, world +Y on the four sides — and u = v × n, which keeps (u, v, n) right-handed
		// for all six faces: a left-handed basis is a reflection, and setFromNormalized would read it as
		// some other rotation entirely. A sign flip in u only mirrors the blob across its own axis.
		Vector3f v = face.getAxis() == Direction.Axis.Y ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
		Vector3f u = new Vector3f(v).cross(n);
		double w = face.getAxis() == Direction.Axis.X ? box.getZsize() : box.getXsize();
		double h = face.getAxis() == Direction.Axis.Y ? box.getZsize() : box.getYsize();
		Vec3 centre = box.getCenter();
		double along = switch (face) {
			case UP -> box.maxY; case DOWN -> box.minY; case EAST -> box.maxX; case WEST -> box.minX; case SOUTH -> box.maxZ; case NORTH -> box.minZ;
		};
		Vec3 faceCentre = switch (face.getAxis()) {
			case X -> new Vec3(along, centre.y, centre.z);
			case Y -> new Vec3(centre.x, along, centre.z);
			case Z -> new Vec3(centre.x, centre.y, along);
		};
		Vec3 world = Vec3.atLowerCornerOf(surface).add(faceCentre).add(new Vec3(n.x, n.y, n.z).scale(LIFT));
		ItemStack stack = new ItemStack(Items.STICK);
		stack.set(DataComponents.ITEM_MODEL, Rivals.id("splat_quad_" + SplatArt.SHAPES[shape]));
		stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color.rgb));
		ItemDisplayElement element = new ItemDisplayElement(stack);
		element.setItemDisplayContext(ItemDisplayContext.FIXED);
		element.setOffset(world.subtract(origin));
		element.setScale(new Vector3f((float) w, (float) h, 1f));
		element.setLeftRotation(new Quaternionf().setFromNormalized(new Matrix3f(u, v, n)));
		return element;
	}
}
