package nu.metacraft.rivals.pack;

import nu.metacraft.rivals.Rivals;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The display quads' art: one generated 16×16 sprite, white so the dye tint on the item display
 * colours it. These are the quads {@link nu.metacraft.rivals.paint.PaintDisplays} hangs on faces no
 * block state can carry paint on (a stair tread, a slab top, a pane, a fence post); the paint in the
 * world itself is block art and lives in {@link PaintArt}.
 *
 * <p>The sprite is the silhouette an <em>isolated</em> paint cell has, by construction rather than by
 * eye: a full square inset one texel on every side with the corners rounded at {@link #RADIUS}, which
 * is the shader's own rounded box with all four sides unconnected, evaluated once per texel centre so
 * the corners come out stepped exactly the way the shader's do. A quad beside a painted block now
 * reads as the same material rather than as a decal from a different game — which the eight Kenney
 * silhouettes it replaces did not, at 32 px and with soft chunky edges of their own.
 *
 * <p>Paint texels carry alpha {@link #PAINT_ALPHA} as the marker the gloss shader reads — the marker
 * belongs to the paint, not to this sprite (which is opaque or nothing), so it is defined here and
 * used in {@link PaintArt}.
 */
public final class SplatArt {
	/** The one quad sprite, model and item id. */
	public static final String QUAD = "paint_quad";
	public static final int SIZE = 16;
	public static final int PAINT_ALPHA = 229;
	/** How far in from the cell's edge the shape starts, in blocks: one texel, as the shader insets. */
	private static final double INSET = 1.0 / SIZE;
	/** The corner radius, in blocks — the shader's 0.28 for a corner with both sides unconnected. */
	private static final double RADIUS = 0.28;

	private SplatArt() {}

	/** Every generated file: the white quad sprite, its model and its item definition. */
	public static Map<String, byte[]> packFiles() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("assets/" + Rivals.MOD_ID + "/textures/item/" + QUAD + ".png", quadSprite());
		files.put("assets/" + Rivals.MOD_ID + "/models/item/" + QUAD + ".json", """
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
				""".formatted(Rivals.MOD_ID + ":item/" + QUAD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		files.put("assets/" + Rivals.MOD_ID + "/items/" + QUAD + ".json", """
				{"model": {"type": "minecraft:model", "model": "%s", "tints": [{"type": "minecraft:dye", "default": 16777215}]}}
				""".formatted(Rivals.MOD_ID + ":item/" + QUAD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return files;
	}

	/** The sprite: opaque white inside the rounded square, fully transparent outside it, nothing between. */
	public static byte[] quadSprite() {
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				if (inside((x + 0.5) / SIZE, (y + 0.5) / SIZE)) image.setRGB(x, y, 0xFFFFFFFF);
			}
		}
		return png(image);
	}

	/**
	 * The shader's rounded-box test, in Java, for the isolated-cell case: centre 0.5, half size
	 * {@code 0.5 - INSET}, radius {@link #RADIUS} at every corner. Kept in the same algebra the
	 * fragment shader uses ({@code length(max(q, 0)) + min(max(q.x, q.y), 0) - r}) so the two shapes
	 * cannot drift apart: a sprite quad lying next to a block cell is the same silhouette.
	 */
	static boolean inside(double px, double py) {
		double half = 0.5 - INSET;
		double qx = Math.abs(px - 0.5) - half + RADIUS;
		double qy = Math.abs(py - 0.5) - half + RADIUS;
		double outside = Math.hypot(Math.max(qx, 0.0), Math.max(qy, 0.0));
		double inner = Math.min(Math.max(qx, qy), 0.0);
		return outside + inner - RADIUS <= 0.0;
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
