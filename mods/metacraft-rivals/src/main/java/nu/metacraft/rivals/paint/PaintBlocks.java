package nu.metacraft.rivals.paint;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;

import java.util.EnumMap;
import java.util.Map;

/** Registers one {@link PaintBlock} per colour as {@code metacraft-rivals:paint_<id>}. No items. */
public final class PaintBlocks {
	private static final Map<PaintColor, PaintBlock> BLOCKS = new EnumMap<>(PaintColor.class);

	private PaintBlocks() {}

	public static PaintBlock of(PaintColor color) {
		return BLOCKS.get(color);
	}

	public static void register() {
		for (PaintColor color : PaintColor.values()) {
			Identifier id = Rivals.id("paint_" + color.id);
			BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
					.noCollision()
					.noOcclusion()
					.instabreak()
					.noLootTable()
					.pushReaction(PushReaction.DESTROY)
					.setId(ResourceKey.create(Registries.BLOCK, id));
			BLOCKS.put(color, Registry.register(BuiltInRegistries.BLOCK, id, new PaintBlock(properties, color)));
		}
	}
}
