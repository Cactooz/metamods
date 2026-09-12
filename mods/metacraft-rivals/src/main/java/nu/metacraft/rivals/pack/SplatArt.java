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
 */
public final class SplatArt {
	public static final String[] SHAPES = {"03", "04", "05", "06", "07", "12", "13", "14"};
	public static final int SIZE = 32;
	public static final int ROTATIONS = 4;
	public static final int PAINT_ALPHA = 229;
	private static final double CROP = 0.72;
	private static final int RIM = 2;
	private static final double RIM_BRIGHTNESS = 0.62;
	/** Vanilla lichen face → model rotation, copied from glow_lichen.json. */
	private static final String[][] FACES = {
			{"north", "", ""}, {"east", "", "90"}, {"south", "", "180"}, {"west", "", "270"}, {"up", "270", ""}, {"down", "90", ""}};

	private SplatArt() {}

	/** Every generated file for the block art: textures, models, and the three blockstate overrides. */
	public static Map<String, byte[]> packFiles() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		for (PaintColor color : PaintColor.values()) {
			for (String shape : SHAPES) {
				for (int rotation = 0; rotation < ROTATIONS; rotation++) {
					String name = "splat_" + color.id + "_" + shape + "_" + rotation;
					files.put("assets/" + Rivals.MOD_ID + "/textures/block/" + name + ".png", texture(color.rgb, shape, rotation));
					files.put("assets/" + Rivals.MOD_ID + "/models/block/" + name + ".json",
							quadModel(Rivals.MOD_ID + ":block/" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				}
			}
			files.put("assets/minecraft/blockstates/" + color.donorPath() + ".json",
					blockstate(color).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		return files;
	}

	/** The tinted, rimmed, rotated 32×32 splat. */
	public static byte[] texture(int rgb, String shape, int rotation) {
		boolean[][] mask = rotate(mask(shape), rotation);
		boolean[][] inner = erode(mask, RIM);
		int fill = (PAINT_ALPHA << 24) | (rgb & 0xFFFFFF);
		int r = (int) (((rgb >> 16) & 0xFF) * RIM_BRIGHTNESS);
		int g = (int) (((rgb >> 8) & 0xFF) * RIM_BRIGHTNESS);
		int b = (int) ((rgb & 0xFF) * RIM_BRIGHTNESS);
		int rim = (PAINT_ALPHA << 24) | (r << 16) | (g << 8) | b;
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				if (mask[y][x]) image.setRGB(x, y, inner[y][x] ? fill : rim);
			}
		}
		return png(image);
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
