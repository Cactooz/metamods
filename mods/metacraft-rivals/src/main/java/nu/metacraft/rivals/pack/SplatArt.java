package nu.metacraft.rivals.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import nu.metacraft.rivals.PaintColor;
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
 * Paint art from Kenney's CC0 Splat Pack: eight chunky silhouettes, cropped so the blob overfills the
 * tile, scaled to 32 px, tinted per colour with a darker rim, and baked in four rotations because a
 * vanilla blockstate cannot rotate a north/south face about its own normal. Paint texels carry alpha
 * {@link #PAINT_ALPHA} as the marker the gloss shader reads. The donor blockstates are replaced by a
 * multipart file whose every face lists all 32 variants, so the client picks one per block position.
 *
 * <p>The block art is animated the way vanilla water is — no shader trickery, just a vertical strip of
 * {@link #FRAMES} frames plus a {@code .png.mcmeta}. Two sheen bands drift across the blob in opposite
 * directions over the loop, which is what makes the paint read as liquid rather than as painted metal.
 */
public final class SplatArt {
	public static final String[] SHAPES = {"03", "04", "05", "06", "07", "12", "13", "14"};
	public static final int SIZE = 32;
	public static final int ROTATIONS = 4;
	public static final int PAINT_ALPHA = 229;
	/** Frames in the animation strip; the texture is SIZE × SIZE * FRAMES, frames stacked top to bottom. */
	public static final int FRAMES = 8;
	/** Ticks per frame, as in water_still.png.mcmeta (which uses 2); interpolation smooths the sweep. */
	private static final int FRAME_TIME = 3;
	private static final double CROP = 0.72;
	private static final int RIM = 2;
	private static final double RIM_BRIGHTNESS = 0.62;
	/** The outermost texel ring, darkened a touch so the blob has a wet meniscus instead of a flat cut. */
	private static final double WET_EDGE = 0.92;
	/** Gaussian half-width of a sheen band, in texels. */
	private static final double BAND_SIGMA = 6.0;
	private static final double BAND_STRENGTH = 0.22;
	private static final double COUNTER_STRENGTH = 0.10;
	private static final String ANIMATION_MCMETA =
			"{\"animation\":{\"frametime\":" + FRAME_TIME + ",\"interpolate\":true}}";
	/** Vanilla lichen face → model rotation, copied from glow_lichen.json. */
	private static final String[][] FACES = {
			{"north", "", ""}, {"east", "", "90"}, {"south", "", "180"}, {"west", "", "270"}, {"up", "270", ""}, {"down", "90", ""}};

	private SplatArt() {}

	/**
	 * Every generated file: the block art (textures, models, the three blockstate overrides) and the
	 * white splat quad item used by the display paint on non-full faces.
	 */
	public static Map<String, byte[]> packFiles() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		for (PaintColor color : PaintColor.values()) {
			for (String shape : SHAPES) {
				for (int rotation = 0; rotation < ROTATIONS; rotation++) {
					String name = "splat_" + color.id + "_" + shape + "_" + rotation;
					files.put("assets/" + Rivals.MOD_ID + "/textures/block/" + name + ".png", strip(color.rgb, shape, rotation));
					files.put("assets/" + Rivals.MOD_ID + "/textures/block/" + name + ".png.mcmeta",
							ANIMATION_MCMETA.getBytes(java.nio.charset.StandardCharsets.UTF_8));
					files.put("assets/" + Rivals.MOD_ID + "/models/block/" + name + ".json",
							quadModel(Rivals.MOD_ID + ":block/" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				}
			}
			files.put("assets/minecraft/blockstates/" + color.donorPath() + ".json",
					blockstate(color).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
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

	/** The animated splat: {@link #FRAMES} frames of the tinted, rimmed, rotated 32×32 art, stacked vertically. */
	public static byte[] strip(int rgb, String shape, int rotation) {
		int[][] base = base(rgb, shape, rotation);
		BufferedImage image = new BufferedImage(SIZE, SIZE * FRAMES, BufferedImage.TYPE_INT_ARGB);
		for (int frame = 0; frame < FRAMES; frame++) draw(image, base, frame, frame * SIZE);
		return png(image);
	}

	/** One 32×32 frame of the strip — frame 0, the same art the animation starts on. */
	public static byte[] texture(int rgb, String shape, int rotation) {
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		draw(image, base(rgb, shape, rotation), 0, 0);
		return png(image);
	}

	/** The un-animated art: fill, darker rim, darker still on the outermost texel ring; 0 where transparent. */
	private static int[][] base(int rgb, String shape, int rotation) {
		boolean[][] mask = rotate(mask(shape), rotation);
		boolean[][] inner = erode(mask, RIM);
		boolean[][] body = erode(mask, 1);
		int[][] base = new int[SIZE][SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				if (!mask[y][x]) continue;
				double shade = inner[y][x] ? 1.0 : RIM_BRIGHTNESS;
				if (!body[y][x]) shade *= WET_EDGE;
				base[y][x] = (PAINT_ALPHA << 24)
						| (scale((rgb >> 16) & 0xFF, shade) << 16)
						| (scale((rgb >> 8) & 0xFF, shade) << 8)
						| scale(rgb & 0xFF, shade);
			}
		}
		return base;
	}

	/**
	 * Writes one frame of the sweep at {@code yOffset}: a wide highlight band travelling down one diagonal
	 * and a fainter one travelling up the other, offset so the two never sit still together. Both positions
	 * advance a constant step per frame and the band's distance wraps modulo the travel period, so the last
	 * frame runs into the first without a seam and no frame repeats another (a sinusoidal ease would stall
	 * at its turning points and make frame 0 and frame FRAMES/2 all but identical).
	 */
	private static void draw(BufferedImage image, int[][] base, int frame, int yOffset) {
		double span = (SIZE - 1) * Math.sqrt(2.0);
		double period = span + 4.0 * BAND_SIGMA;
		double step = period * frame / FRAMES;
		double center = step;
		double counter = period / 3.0 - step;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int color = base[y][x];
				if (color == 0) continue;
				double band = gaussian((x + y) / Math.sqrt(2.0) - center, period);
				double back = gaussian((x + (SIZE - 1 - y)) / Math.sqrt(2.0) - counter, period);
				color = whiten(color, BAND_STRENGTH * band);
				color = whiten(color, COUNTER_STRENGTH * back);
				image.setRGB(x, yOffset + y, color);
			}
		}
	}

	/** A gaussian of the distance folded into one travel period, which is what makes the loop close. */
	private static double gaussian(double distance, double period) {
		double wrapped = distance - period * Math.round(distance / period);
		return Math.exp(-(wrapped * wrapped) / (2.0 * BAND_SIGMA * BAND_SIGMA));
	}

	/** Blends toward white, leaving the alpha marker untouched. */
	private static int whiten(int argb, double amount) {
		return (argb & 0xFF000000)
				| (mixWhite((argb >> 16) & 0xFF, amount) << 16)
				| (mixWhite((argb >> 8) & 0xFF, amount) << 8)
				| mixWhite(argb & 0xFF, amount);
	}

	private static int mixWhite(int channel, double amount) {
		return Math.clamp(Math.round(channel + (255 - channel) * amount), 0, 255);
	}

	private static int scale(int channel, double factor) {
		return Math.clamp((int) (channel * factor), 0, 255);
	}

	/** The same silhouette as an opaque white shape (alpha 255) for the dye-tinted display quads. */
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

	/** Vanilla's lichen quad model, pointing at one of our textures. */
	public static String quadModel(String texture) {
		return """
				{
					"ambientocclusion": false,
					"textures": {"particle": "%1$s", "splat": "%1$s"},
					"elements": [{
						"from": [0, 0, 0.1],
						"to": [16, 16, 0.1],
						"faces": {
							"north": {"uv": [16, 0, 0, 16], "texture": "#splat"},
							"south": {"uv": [0, 0, 16, 16], "texture": "#splat"}
						}
					}]
				}
				""".formatted(texture);
	}

	/** The multipart override for a colour's donor block: vanilla's structure, every face listing all 32 variants. */
	public static String blockstate(PaintColor color) {
		JsonArray multipart = new JsonArray();
		for (String[] face : FACES) {
			JsonObject when = new JsonObject();
			when.addProperty(face[0], "true");
			multipart.add(part(when, color, face[1], face[2]));
		}
		JsonObject none = new JsonObject();
		for (String[] face : FACES) none.addProperty(face[0], "false");
		multipart.add(part(none, color, "", ""));
		JsonObject root = new JsonObject();
		root.add("multipart", multipart);
		return root.toString();
	}

	private static JsonObject part(JsonObject when, PaintColor color, String x, String y) {
		JsonArray apply = new JsonArray();
		for (String shape : SHAPES) {
			for (int rotation = 0; rotation < ROTATIONS; rotation++) {
				JsonObject variant = new JsonObject();
				variant.addProperty("model", Rivals.MOD_ID + ":block/splat_" + color.id + "_" + shape + "_" + rotation);
				if (!x.isEmpty()) variant.addProperty("x", Integer.parseInt(x));
				if (!y.isEmpty()) variant.addProperty("y", Integer.parseInt(y));
				variant.addProperty("uvlock", true);
				variant.addProperty("weight", 1);
				apply.add(variant);
			}
		}
		JsonObject part = new JsonObject();
		part.add("when", when);
		part.add("apply", apply);
		return part;
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

	static boolean[][] rotate(boolean[][] mask, int quarterTurns) {
		boolean[][] out = mask;
		for (int t = 0; t < quarterTurns % 4; t++) {
			boolean[][] next = new boolean[SIZE][SIZE];
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++) next[x][SIZE - 1 - y] = out[y][x];
			}
			out = next;
		}
		return out;
	}

	static boolean[][] erode(boolean[][] mask, int radius) {
		boolean[][] out = new boolean[SIZE][SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				boolean keep = mask[y][x];
				for (int dy = -radius; keep && dy <= radius; dy++) {
					for (int dx = -radius; keep && dx <= radius; dx++) {
						int nx = x + dx, ny = y + dy;
						if (nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE || !mask[ny][nx]) keep = false;
					}
				}
				out[y][x] = keep;
			}
		}
		return out;
	}

	private static byte[] png(BufferedImage image) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer available");
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException("could not encode splat art", e);
		}
	}
}
