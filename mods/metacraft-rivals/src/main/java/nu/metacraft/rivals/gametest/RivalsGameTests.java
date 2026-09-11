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
}
