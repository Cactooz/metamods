package nu.metacraft.rivals.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.paint.PaintBlock;
import nu.metacraft.rivals.paint.PaintBlocks;
import nu.metacraft.rivals.paint.Painter;
import nu.metacraft.rivals.paint.PaintTally;
import nu.metacraft.rivals.pack.SplatTexture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Server-side game tests (Fabric GameTest API). Run headless with
 * {@code ./gradlew mods:metacraft-rivals:runGameTest}; each test gets an empty 8×8×8 structure and
 * positions passed to the helper are relative to it.
 */
public final class RivalsGameTests {
	@GameTest
	public void modLoads(GameTestHelper helper) {
		helper.succeed();
	}

	/** Every paint state is sent as its donor block with the same six face flags and never waterlogged. */
	@GameTest
	public void donorMappingKeepsFaces(GameTestHelper helper) {
		for (PaintColor color : PaintColor.values()) {
			PaintBlock block = PaintBlocks.of(color);
			BlockState state = block.defaultBlockState()
					.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
					.setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true);
			BlockState client = block.getPolymerBlockState(state, PacketContext.get());
			helper.assertTrue(client.is(color.donor), Component.literal(color.id + " maps to " + client));
			for (Direction d : Direction.values()) {
				boolean expected = d == Direction.DOWN || d == Direction.NORTH;
				helper.assertTrue(client.getValue(MultifaceBlock.getFaceProperty(d)) == expected,
						Component.literal(color.id + ": face " + d + " should be " + expected));
			}
			helper.assertTrue(!client.getValue(MultifaceBlock.WATERLOGGED), Component.literal(color.id + " sent waterlogged"));
		}
		helper.succeed();
	}

	/** The generated splat is a 16×16 PNG: transparent outside the blob, the colour inside. */
	@GameTest
	public void splatTextureIsColouredBlob(GameTestHelper helper) throws IOException {
		int rgb = 0xEA2C8E;
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(SplatTexture.png(rgb, 0)));
		helper.assertTrue(image != null, "PNG decodes");
		helper.assertValueEqual(image.getWidth(), SplatTexture.SIZE, "width");
		helper.assertValueEqual(image.getHeight(), SplatTexture.SIZE, "height");
		int opaque = 0;
		int transparent = 0;
		int exactColour = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int argb = image.getRGB(x, y);
				int alpha = (argb >>> 24) & 0xFF;
				if (alpha == 0) transparent++;
				else if (alpha == 0xFF) opaque++;
				if (argb == (0xFF000000 | rgb)) exactColour++;
			}
		}
		helper.assertTrue(opaque > 0, "has opaque pixels");
		helper.assertTrue(transparent > 0, "has transparent pixels");
		helper.assertTrue(opaque + transparent == SplatTexture.SIZE * SplatTexture.SIZE, "no half-transparent pixels");
		helper.assertTrue(exactColour > 0, "fill pixels are the exact colour");
		helper.succeed();
	}

	/** Stone floor at relative y=1 over x,z in [0,size). */
	private static void stoneFloor(GameTestHelper helper, int size) {
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
	}

	private static int faces(BlockState state) {
		int n = 0;
		for (Direction d : Direction.values()) {
			if (state.getValue(MultifaceBlock.getFaceProperty(d))) n++;
		}
		return n;
	}

	/** A splat on the top of a floor block paints the cell above it, on its down face, in that colour. */
	@GameTest
	public void floorSplatPaintsCellAbove(GameTestHelper helper) {
		stoneFloor(helper, 5);
		BlockPos struck = new BlockPos(2, 1, 2);
		int painted = Painter.splat(helper.getLevel(), helper.absolutePos(struck), Direction.UP, PaintColor.MAGENTA,
				helper.getLevel().getRandom());
		helper.assertTrue(painted >= 5 && painted <= 9, "painted " + painted + " faces, expected 5..9");
		BlockState cell = helper.getBlockState(struck.above());
		helper.assertTrue(cell.is(PaintBlocks.of(PaintColor.MAGENTA)), Component.literal("cell above the hit is magenta paint, got " + cell));
		helper.assertTrue(cell.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)), "paint sits on its down face");
		helper.succeed();
	}

	/** The blob stays within one block of the hit in the plane, and never where the surface is missing. */
	@GameTest
	public void blobStaysWithinRadiusAndOnSurfaces(GameTestHelper helper) {
		stoneFloor(helper, 5);
		helper.setBlock(new BlockPos(1, 1, 2), Blocks.AIR); // a hole beside the hit, not a corner
		Painter.splat(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)), Direction.UP, PaintColor.LIME,
				helper.getLevel().getRandom());
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				BlockState cell = helper.getBlockState(new BlockPos(x, 2, z));
				boolean inBlob = Math.abs(x - 2) <= Painter.RADIUS && Math.abs(z - 2) <= Painter.RADIUS;
				boolean overHole = x == 1 && z == 2;
				if (!inBlob || overHole) {
					helper.assertTrue(cell.isAir(), Component.literal("no paint expected at " + x + "," + z + " but found " + cell));
				}
			}
		}
		helper.assertTrue(helper.getBlockState(new BlockPos(2, 2, 2)).is(PaintBlocks.of(PaintColor.LIME)), "centre is painted");
		for (BlockPos edge : new BlockPos[] {new BlockPos(3, 2, 2), new BlockPos(2, 2, 1), new BlockPos(2, 2, 3)}) {
			BlockState cell = helper.getBlockState(edge);
			helper.assertTrue(cell.is(PaintBlocks.of(PaintColor.LIME)), Component.literal("edge " + edge + " should be lime paint, got " + cell));
			helper.assertTrue(cell.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)), "edge " + edge + " has its down face set");
		}
		helper.assertTrue(helper.getBlockState(new BlockPos(1, 2, 2)).isAir(), "edge over the hole stays air");
		helper.succeed();
	}

	/** A hit in another colour recolours the whole cell and keeps its faces. */
	@GameTest
	public void otherColourRecoloursCellKeepingFaces(GameTestHelper helper) {
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE); // floor under the cell
		helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE); // wall north of the cell
		BlockPos cell = new BlockPos(2, 2, 2);
		helper.setBlock(cell, PaintBlocks.of(PaintColor.MAGENTA).defaultBlockState()
				.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
				.setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true));
		boolean painted = Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)), Direction.UP, PaintColor.LIME);
		helper.assertTrue(painted, "the cell counts as newly painted");
		BlockState after = helper.getBlockState(cell);
		helper.assertTrue(after.is(PaintBlocks.of(PaintColor.LIME)), Component.literal("cell is lime now, got " + after));
		helper.assertTrue(after.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)), "down face kept");
		helper.assertTrue(after.getValue(MultifaceBlock.getFaceProperty(Direction.NORTH)), "north face kept");
		helper.assertValueEqual(faces(after), 2, "face count");
		helper.succeed();
	}

	/** The tally counts faces per colour from the cells it tracks, and reset removes them. */
	@GameTest
	public void tallyCountsFacesAndResets(GameTestHelper helper) {
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
		helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
		helper.setBlock(new BlockPos(4, 1, 4), Blocks.STONE);
		BlockPos magentaCell = new BlockPos(2, 2, 2);
		BlockPos limeCell = new BlockPos(4, 2, 4);
		helper.setBlock(magentaCell, PaintBlocks.of(PaintColor.MAGENTA).defaultBlockState()
				.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
				.setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true));
		helper.setBlock(limeCell, PaintBlocks.of(PaintColor.LIME).defaultBlockState()
				.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
		PaintTally tally = new PaintTally();
		tally.track(helper.absolutePos(magentaCell));
		tally.track(helper.absolutePos(limeCell));
		tally.track(helper.absolutePos(new BlockPos(0, 5, 0))); // air: must be dropped, not counted
		Map<PaintColor, Integer> counts = tally.count(helper.getLevel());
		helper.assertValueEqual(counts.get(PaintColor.MAGENTA), 2, "magenta faces");
		helper.assertValueEqual(counts.get(PaintColor.LIME), 1, "lime faces");
		helper.assertValueEqual(counts.get(PaintColor.CYAN), 0, "cyan faces");
		helper.assertValueEqual(tally.cells(), 2, "the air cell was dropped");
		helper.assertTrue(Math.abs(PaintTally.share(counts, PaintColor.LIME) - 1f / 3f) < 1e-6, "lime share is a third");
		int removed = tally.reset(helper.getLevel());
		helper.assertValueEqual(removed, 2, "reset removed both cells");
		helper.assertTrue(helper.getBlockState(magentaCell).isAir() && helper.getBlockState(limeCell).isAir(), "cells are air after reset");
		helper.assertValueEqual(tally.count(helper.getLevel()).get(PaintColor.MAGENTA), 0, "nothing left to count");
		helper.succeed();
	}
}
