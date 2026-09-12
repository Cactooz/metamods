package nu.metacraft.rivals.pack;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
			// The count covers more than the splat art: the per-colour blockstate overrides and the
			// splat-quad item files ride along in the same map, so name them rather than let the number
			// read as "colours × shapes × rotations" and not add up.
			long quadFiles = files.keySet().stream().filter(path -> path.contains("splat_quad_")).count();
			Rivals.LOGGER.info("[{}] pack: {} pack files (splat art for {} colours × {} shapes × {} rotations, plus {} quad item files)",
					Rivals.MOD_ID, files.size(), PaintColor.values().length, SplatArt.SHAPES.length, SplatArt.ROTATIONS, quadFiles);
			builder.addData("assets/minecraft/shaders/core/block.vsh", shader("block.vsh"));
			builder.addData("assets/minecraft/shaders/core/block.fsh", shader("block.fsh"));
		});
	}

	/** A shader file from the mod's resources, as shipped under assets/minecraft/shaders/core. */
	public static byte[] shader(String name) {
		try (InputStream in = RivalsPack.class.getResourceAsStream("/rivals_shaders/" + name)) {
			if (in == null) throw new IllegalStateException("[" + Rivals.MOD_ID + "] missing shader " + name);
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException("could not read shader " + name, e);
		}
	}
}
