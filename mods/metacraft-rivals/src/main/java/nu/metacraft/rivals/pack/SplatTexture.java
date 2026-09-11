package nu.metacraft.rivals.pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/**
 * A procedural 16×16 paint splat: a few overlapping discs plus stray drips, filled with the colour and
 * rimmed one texel darker. Deterministic per seed so every server build produces the same pack.
 * Replacing it with real art is dropping a PNG at the donor texture path instead.
 */
public final class SplatTexture {
	public static final int SIZE = 16;

	private SplatTexture() {}

	public static byte[] png(int rgb, int seed) {
		boolean[][] mask = mask(seed);
		int fill = 0xFF000000 | (rgb & 0xFFFFFF);
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		int rim = 0xFF000000 | ((r * 3 / 4) << 16) | ((g * 3 / 4) << 8) | (b * 3 / 4);
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				if (!mask[y][x]) continue;
				image.setRGB(x, y, isRim(mask, x, y) ? rim : fill);
			}
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer available");
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException("could not encode the splat texture", e);
		}
	}

	/** Union of three or four discs of radius 3 to 6 near the centre, plus four single-texel drips. */
	static boolean[][] mask(int seed) {
		Random random = new Random(0x5EED1234L + seed * 7919L);
		boolean[][] mask = new boolean[SIZE][SIZE];
		int discs = 3 + random.nextInt(2);
		for (int i = 0; i < discs; i++) {
			double cx = 4 + random.nextDouble() * 8;
			double cy = 4 + random.nextDouble() * 8;
			double radius = 3 + random.nextDouble() * 3;
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++) {
					double dx = x + 0.5 - cx;
					double dy = y + 0.5 - cy;
					if (dx * dx + dy * dy <= radius * radius) mask[y][x] = true;
				}
			}
		}
		for (int i = 0; i < 4; i++) {
			mask[random.nextInt(SIZE)][random.nextInt(SIZE)] = true;
		}
		return mask;
	}

	private static boolean isRim(boolean[][] mask, int x, int y) {
		return !inside(mask, x - 1, y) || !inside(mask, x + 1, y) || !inside(mask, x, y - 1) || !inside(mask, x, y + 1);
	}

	private static boolean inside(boolean[][] mask, int x, int y) {
		return x >= 0 && y >= 0 && x < SIZE && y < SIZE && mask[y][x];
	}
}
