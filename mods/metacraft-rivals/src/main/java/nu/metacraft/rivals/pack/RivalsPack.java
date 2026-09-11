package nu.metacraft.rivals.pack;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;

/**
 * The resource pack: the mod's own assets (gun model, palette, lang) plus one generated splat texture
 * per colour written over the donor block's vanilla texture. Required, because without it players see
 * sculk veins and resin clumps where the paint is.
 */
public final class RivalsPack {
	private RivalsPack() {}

	public static void init() {
		PolymerResourcePackUtils.addModAssets(Rivals.MOD_ID);
		PolymerResourcePackUtils.markAsRequired();
		PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> {
			for (PaintColor color : PaintColor.values()) {
				builder.addData(color.donorTexturePath(), SplatTexture.png(color.rgb, color.ordinal()));
				Rivals.LOGGER.info("[{}] pack: {} splat over {}", Rivals.MOD_ID, color.id, color.donorTexturePath());
			}
		});
	}
}
