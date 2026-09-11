package nu.metacraft.rivals.paint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import nu.metacraft.rivals.PaintColor;

/**
 * Where a hit puts paint. The struck block is the surface; the paint lives in the cell in front of the
 * struck face, as a paint block whose face flag points back at the surface. A splat covers the 3×3 of
 * surface blocks around the hit in the plane of the face, corners dropped at random.
 */
public final class Painter {
	public static final int RADIUS = 1;

	private Painter() {}

	/** Paint a blob around {@code struck}'s {@code face}. Returns how many faces are newly this colour. */
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
	 * Paint one face: the {@code face} side of the block at {@code surface}. The cell in front must be air
	 * or paint, and the surface must be something a multiface block can attach to. An air cell becomes
	 * this colour with that face; a same-colour cell gains the face; another colour's cell is recoloured
	 * whole, keeping its faces. Returns whether anything changed.
	 */
	public static boolean paintFace(ServerLevel level, BlockPos surface, Direction face, PaintColor color) {
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
			for (Direction d : Direction.values()) {
				BooleanProperty property = MultifaceBlock.getFaceProperty(d);
				next = next.setValue(property, existing.getValue(property));
			}
			next = next.setValue(attachFace, true);
		} else {
			return false;
		}
		if (!MultifaceBlock.canAttachTo(level, attach, surface, level.getBlockState(surface))) return false;
		level.setBlock(cell, next, Block.UPDATE_ALL);
		PaintTally.of(level).track(cell);
		return true;
	}
}
