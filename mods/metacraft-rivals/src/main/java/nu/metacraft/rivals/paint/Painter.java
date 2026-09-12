package nu.metacraft.rivals.paint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import nu.metacraft.rivals.PaintColor;
import org.jspecify.annotations.Nullable;

/**
 * Where a hit puts paint. The struck block is the surface; a full face takes a paint block in the cell
 * in front, whose face flag points back at the surface. Any other shape (stairs, slabs, fences, panes)
 * takes {@link PaintDisplays} quads instead, wrapped around the block's own collision boxes. A splat
 * covers the 3×3 of surface blocks around the hit in the plane of the face, corners dropped at random.
 */
public final class Painter {
	public static final int RADIUS = 1;
	private static final Direction[] DIRECTIONS = Direction.values();

	private Painter() {}

	/**
	 * Paint a blob around {@code struck}'s {@code face}. Returns how many cells changed; a recolour counts
	 * once however many faces it flips.
	 */
	public static int splat(ServerLevel level, BlockPos struck, Direction face, PaintColor color, RandomSource random) {
		int painted = 0;
		for (int a = -RADIUS; a <= RADIUS; a++) {
			for (int b = -RADIUS; b <= RADIUS; b++) {
				boolean corner = Math.abs(a) == RADIUS && Math.abs(b) == RADIUS;
				if (corner && random.nextBoolean()) continue;
				if (paintFace(level, offsetInPlane(struck, face.getAxis(), a, b), face, color)) painted++;
			}
		}
		return painted;
	}

	/** Offset {@code origin} by (a, b) within the plane perpendicular to {@code normal}. */
	static BlockPos offsetInPlane(BlockPos origin, Direction.Axis normal, int a, int b) {
		return switch (normal) {
			case Y -> origin.offset(a, 0, b);
			case X -> origin.offset(0, a, b);
			case Z -> origin.offset(a, b, 0);
		};
	}

	/**
	 * Anything solid: not air, not replaceable (grass, snow), not a liquid, not paint. Waterlogged blocks
	 * hold paint like any other — the test is the block, not the fluid state it carries.
	 */
	public static boolean paintable(BlockState surface) {
		return !surface.isAir() && !surface.canBeReplaced() && !(surface.getBlock() instanceof LiquidBlock) && !(surface.getBlock() instanceof PaintBlock);
	}

	/**
	 * Paint one face: the {@code face} side of the block at {@code surface}. The surface must be solid; a
	 * face that is not full takes display quads instead of a block. Otherwise the cell in front must be air
	 * or paint: an air cell becomes this colour with that face; a same-colour cell gains the face; another
	 * colour's cell is recoloured whole, keeping its faces. Returns whether anything changed.
	 */
	public static boolean paintFace(ServerLevel level, BlockPos surface, Direction face, PaintColor color) {
		BlockState surfaceState = level.getBlockState(surface);
		if (!paintable(surfaceState)) return false;
		if (!Block.isFaceFull(surfaceState.getCollisionShape(level, surface), face)) {
			return PaintDisplays.of(level).paint(level, surface, face, color);
		}
		BlockPos cell = surface.relative(face);
		Direction attach = face.getOpposite();
		BooleanProperty attachFace = MultifaceBlock.getFaceProperty(attach);
		BlockState existing = level.getBlockState(cell);
		BlockState next;
		if (existing.isAir()) {
			next = PaintBlocks.of(color).defaultBlockState().setValue(attachFace, true);
		} else if (existing.getBlock() instanceof PaintBlock paint) {
			if (paint.color == color && existing.getValue(attachFace)) return false;
			next = PaintBlocks.of(color).defaultBlockState();
			for (Direction d : DIRECTIONS) {
				BooleanProperty property = MultifaceBlock.getFaceProperty(d);
				next = next.setValue(property, existing.getValue(property));
			}
			next = next.setValue(attachFace, true);
		} else {
			return false;
		}
		if (!level.setBlock(cell, next, Block.UPDATE_ALL)) return false;
		PaintTally.of(level).track(cell);
		return true;
	}

	/** Ray length from the impact point, in blocks. */
	public static final double RAY_LENGTH = 1.5;
	/** The six axis directions and the eight body diagonals, unit length. */
	public static final Vec3[] RAY_DIRECTIONS = rayDirections();

	private static Vec3[] rayDirections() {
		Vec3[] rays = new Vec3[14];
		int i = 0;
		for (Direction d : DIRECTIONS) rays[i++] = Vec3.atLowerCornerOf(d.getUnitVec3i());
		for (int x = -1; x <= 1; x += 2) {
			for (int y = -1; y <= 1; y += 2) {
				for (int z = -1; z <= 1; z += 2) rays[i++] = new Vec3(x, y, z).normalize();
			}
		}
		return rays;
	}

	/**
	 * A full impact: the 3×3 blob on the struck face, then fourteen short rays from the impact point that
	 * paint whatever face they hit (so a floor shot beside a wall also paints the wall and the corner),
	 * with a coloured dust burst and a wet sound. Returns how many cells changed.
	 */
	public static int splash(ServerLevel level, Vec3 impact, BlockPos struck, Direction face, PaintColor color,
			RandomSource random, @Nullable Entity source) {
		int changed = splat(level, struck, face, color, random);
		Vec3 from = impact.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.05));
		DustParticleOptions dust = new DustParticleOptions(color.rgb, 1.6f);
		for (Vec3 ray : RAY_DIRECTIONS) {
			Vec3 to = from.add(ray.scale(RAY_LENGTH));
			ClipContext context = source != null
					? new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source)
					: new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
			BlockHitResult hit = level.clip(context);
			if (hit.getType() != HitResult.Type.BLOCK) continue;
			if (paintFace(level, hit.getBlockPos(), hit.getDirection(), color)) changed++;
			Vec3 at = hit.getLocation();
			level.sendParticles(dust, at.x, at.y, at.z, 4, 0.1, 0.1, 0.1, 0.01);
		}
		level.sendParticles(dust, impact.x, impact.y, impact.z, 24, 0.35, 0.35, 0.35, 0.02);
		level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.SLIME_BLOCK_HIT, SoundSource.BLOCKS, 0.8f, 1.3f);
		return changed;
	}
}
