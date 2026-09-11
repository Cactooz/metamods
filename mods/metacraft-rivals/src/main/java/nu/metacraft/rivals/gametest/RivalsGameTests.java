package nu.metacraft.rivals.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.paint.PaintBlock;
import nu.metacraft.rivals.paint.PaintBlocks;
import nu.metacraft.rivals.pack.SplatTexture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

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
}
