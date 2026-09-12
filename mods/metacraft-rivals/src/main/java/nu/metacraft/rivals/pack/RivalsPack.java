package nu.metacraft.rivals.pack;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;

import java.util.Map;

/**
 * The resource pack: the mod's own assets (gun model, palette, lang) plus generated splat art per
 * colour, shipped as a multipart blockstate override replacing the donor block's vanilla blockstate.
 * Required, because without it players see sculk veins and resin clumps where the paint is.
 */
public final class RivalsPack {
	private RivalsPack() {}

	public static void init() {
		PolymerResourcePackUtils.addModAssets(Rivals.MOD_ID);
		PolymerResourcePackUtils.markAsRequired();
		PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> {
			Map<String, byte[]> files = SplatArt.packFiles();
			files.forEach(builder::addData);
			Rivals.LOGGER.info("[{}] pack: {} splat files written ({} colours × {} shapes × {} rotations)", Rivals.MOD_ID,
					files.size(), PaintColor.values().length, SplatArt.SHAPES.length, SplatArt.ROTATIONS);
		});
	}
}
