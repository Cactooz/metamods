package nu.metacraft.rivals.pack;

import nu.metacraft.rivals.Rivals;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The display quads' art from Kenney's CC0 Splat Pack: eight chunky silhouettes, cropped so the blob
 * overfills the tile and scaled to 32 px, kept as opaque white so the dye tint on the item display
 * entity colours them. These are the splats {@link nu.metacraft.rivals.paint.PaintDisplays} hangs on
 * faces no block state can carry paint on; the paint in the world itself is block art now and lives
 * in {@link PaintArt}. Paint texels carry alpha {@link #PAINT_ALPHA} as the marker the gloss shader
 * reads — the marker belongs to the paint, not to the silhouette, so it is defined here and used there.
 */
public final class SplatArt {
	public static final String[] SHAPES = {"03", "04", "05", "06", "07", "12", "13", "14"};
	public static final int SIZE = 32;
	public static final int PAINT_ALPHA = 229;
	private static final double CROP = 0.72;

	private SplatArt() {}

	/** Every generated file: the white splat quad item — texture, model and item definition — per shape. */
	public static Map<String, byte[]> packFiles() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		// One white silhouette per shape for the display quads on non-full faces: the dye tint colours it.
		for (String shape : SHAPES) {
			String name = "splat_quad_" + shape;
			files.put("assets/" + Rivals.MOD_ID + "/textures/item/" + name + ".png", whiteMask(shape));
			files.put("assets/" + Rivals.MOD_ID + "/models/item/" + name + ".json", """
					{
						"textures": {"particle": "%1$s", "splat": "%1$s"},
						"elements": [{
							"from": [0, 0, 8],
							"to": [16, 16, 8],
							"faces": {
								"north": {"uv": [16, 0, 0, 16], "texture": "#splat", "tintindex": 0},
								"south": {"uv": [0, 0, 16, 16], "texture": "#splat", "tintindex": 0}
							}
						}],
						"display": {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
					}
					""".formatted(Rivals.MOD_ID + ":item/" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			files.put("assets/" + Rivals.MOD_ID + "/items/" + name + ".json", """
					{"model": {"type": "minecraft:model", "model": "%s", "tints": [{"type": "minecraft:dye", "default": 16777215}]}}
					""".formatted(Rivals.MOD_ID + ":item/" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		return files;
	}

	/** The silhouette as an opaque white shape (alpha 255) for the dye-tinted display quads. */
	public static byte[] whiteMask(String shape) {
		boolean[][] mask = mask(shape);
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				if (mask[y][x]) image.setRGB(x, y, 0xFFFFFFFF);
			}
		}
		return png(image);
	}

	/** The Kenney sprite's alpha, centre-cropped and scaled to 32×32, thresholded. */
	static boolean[][] mask(String shape) {
		BufferedImage source;
		try (InputStream in = SplatArt.class.getResourceAsStream("/kenney/splat/splat" + shape + ".png")) {
			if (in == null) throw new IllegalStateException("[" + Rivals.MOD_ID + "] missing Kenney splat " + shape);
			source = ImageIO.read(in);
		} catch (IOException e) {
			throw new UncheckedIOException("could not read Kenney splat " + shape, e);
		}
		int side = (int) (source.getWidth() * CROP);
		int offset = (source.getWidth() - side) / 2;
		boolean[][] mask = new boolean[SIZE][SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				// Box-filter the source block that maps onto this texel: opaque if more than half of it is.
				int sx0 = offset + x * side / SIZE, sx1 = offset + (x + 1) * side / SIZE;
				int sy0 = offset + y * side / SIZE, sy1 = offset + (y + 1) * side / SIZE;
				long alpha = 0;
				int n = 0;
				for (int sy = sy0; sy < sy1; sy++) {
					for (int sx = sx0; sx < sx1; sx++) {
						alpha += (source.getRGB(sx, sy) >>> 24) & 0xFF;
						n++;
					}
				}
				mask[y][x] = n > 0 && alpha / n > 128;
			}
		}
		return mask;
	}

	/** PNG bytes, the one encoder both art generators use. */
	static byte[] png(BufferedImage image) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer available");
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException("could not encode splat art", e);
		}
	}
}
