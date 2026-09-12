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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paint for faces a multiface block cannot sit on (stairs, slabs, fences, panes …): one flat splat quad
 * per collision-box face on the struck side, as Polymer item displays that wrap the block's real shape.
 * One holder per cell, one colour per cell; recolouring rebuilds the holder. In memory only, like the
 * tally: a restart drops them.
 */
public final class PaintDisplays {
	private static final Map<ResourceKey<Level>, PaintDisplays> ALL = new HashMap<>();
	private static final double LIFT = 0.01;

	private record Painted(PaintColor color, ElementHolder holder, int quads) {}

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

	/** Quads per colour, counted as faces. Every colour has an entry. */
	public Map<PaintColor, Integer> count() {
		Map<PaintColor, Integer> counts = new EnumMap<>(PaintColor.class);
		for (PaintColor color : PaintColor.values()) counts.put(color, 0);
		for (Painted painted : cells.values()) counts.merge(painted.color, painted.quads, Integer::sum);
		return counts;
	}

	/** Destroy every holder in this level. Returns how many cells were cleared. */
	public int clear() {
		int n = cells.size();
		cells.values().forEach(painted -> painted.holder.destroy());
		cells.clear();
		return n;
	}

	/**
	 * Cover the {@code face} side of every collision box of the block at {@code surface} with quads in the
	 * cell in front. Returns false when the cell already holds this colour or the shape has no boxes.
	 */
	public boolean paint(ServerLevel level, BlockPos surface, Direction face, PaintColor color) {
		BlockPos cell = surface.relative(face).immutable();
		Painted existing = cells.get(cell);
		if (existing != null && existing.color == color) return false;
		BlockState state = level.getBlockState(surface);
		VoxelShape shape = state.getCollisionShape(level, surface);
		if (shape.isEmpty()) shape = state.getShape(level, surface);
		List<AABB> boxes = shape.toAabbs();
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
		cells.put(cell, new Painted(color, holder, quads));
		return true;
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
