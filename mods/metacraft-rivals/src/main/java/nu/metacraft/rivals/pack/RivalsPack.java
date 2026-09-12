package nu.metacraft.rivals.pack;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.Direction;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.paint.PaintStates;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * The resource pack: the mod's own assets (gun model, palette, lang) plus the generated paint art —
 * bit-carrying textures, the six face quads and a variants blockstate override per donor block — and
 * the white splat quads the display paint uses. Required, because without it players see sculk veins,
 * resin clumps, tripwire and redstone dust where the paint is. Also ships a terrain shader override:
 * chunk geometry in 26.3 is drawn by terrain.vsh/terrain.fsh (not block.*), so the paint gloss is
 * keyed into that pair instead.
 */
public final class RivalsPack {
	private RivalsPack() {}

	public static void init() {
		PolymerResourcePackUtils.addModAssets(Rivals.MOD_ID);
		PolymerResourcePackUtils.markAsRequired();
		PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> {
			Map<String, byte[]> paint = PaintArt.packFiles();
			Map<String, byte[]> splats = SplatArt.packFiles();
			paint.forEach(builder::addData);
			splats.forEach(builder::addData);
			// Name every group the paint map holds, with its own count, so the total adds up when read:
			// bit textures + face models + wrappers + mask models + the empty model + donor overrides.
			int colors = PaintColor.values().length;
			Rivals.LOGGER.info(
					"[{}] pack: {} paint files ({} bit textures, {} face models, {} wrappers, {} mask models, 1 empty model, {} donor overrides), {} splat-quad files, terrain shader",
					Rivals.MOD_ID, paint.size(), colors * PaintArt.BITS, Direction.values().length,
					colors * PaintArt.BITS * Direction.values().length, colors * PaintStates.SPLAT_PER_COLOR,
					PaintStates.DONORS.size(), splats.size());
			builder.addData("assets/minecraft/shaders/core/terrain.vsh", shader("terrain.vsh"));
			builder.addData("assets/minecraft/shaders/core/terrain.fsh", shader("terrain.fsh"));
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
