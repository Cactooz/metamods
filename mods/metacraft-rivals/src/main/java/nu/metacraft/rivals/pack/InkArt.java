package nu.metacraft.rivals.pack;

import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.gun.InkOnScreen;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pack side of the ink on the screen (spec: v6 §B): the post effect, its two shaders and the font
 * whose one glyph is the data pixel the server writes the meter into.
 *
 * <p>The post effect is called {@code minecraft:end_of_frame}, which is the one hook a server-side mod
 * has on a vanilla client's frame: 26.3's {@code GameRenderer.update} asks for that chain every single
 * frame and drops the request silently when no pack defines it. A pack that does define it therefore
 * gets one full-screen pass per frame, with nothing to trigger and nothing to turn it on.
 *
 * <p>The glyph is a 2x2 white square drawn at a colour that is the meter — see {@link InkOnScreen} for
 * the encoding. Two pixels is the smallest thing the probe can find reliably: a title is scaled four
 * times and then by the GUI scale, so eight screen pixels is the narrowest it can ever come out.
 */
public final class InkArt {
	/** The font the data pixel is drawn in: the name and the character are {@link InkOnScreen}'s. */
	public static final String FONT = InkOnScreen.FONT;
	public static final int GLYPH_SIZE = 2;
	/** The post effect id vanilla asks for every frame. */
	public static final String POST_EFFECT = "end_of_frame";
	/** The two fragment shaders the chain runs: the data-pixel search, then the ink itself. */
	public static final String PROBE_SHADER = "ink_probe";
	public static final String INK_SHADER = "ink";

	private InkArt() {}

	/** Every file the ink needs: the chain, its shaders, the font and the font's texture. */
	public static Map<String, byte[]> packFiles() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("assets/minecraft/post_effect/" + POST_EFFECT + ".json", resource(POST_EFFECT + ".json"));
		for (String shader : new String[] {PROBE_SHADER, INK_SHADER}) {
			files.put("assets/" + Rivals.MOD_ID + "/shaders/post/" + shader + ".fsh", resource(shader + ".fsh"));
		}
		files.put("assets/" + Rivals.MOD_ID + "/font/" + FONT + ".json", font());
		files.put("assets/" + Rivals.MOD_ID + "/textures/font/" + FONT + ".png", glyph());
		return files;
	}

	/**
	 * The font: one bitmap provider, one character, height and ascent both the sprite's own size, so the
	 * glyph is drawn one text pixel per texel and comes out a solid square rather than a letter.
	 */
	static byte[] font() {
		return ("""
				{
					"providers": [
						{
							"type": "bitmap",
							"file": "%s:font/%s.png",
							"ascent": %d,
							"height": %d,
							"chars": ["\\u%04X"]
						}
					]
				}
				""".formatted(Rivals.MOD_ID, FONT, GLYPH_SIZE, GLYPH_SIZE, (int) InkOnScreen.MARKER))
				.getBytes(StandardCharsets.UTF_8);
	}

	/** The sprite: opaque white, every texel. The colour comes from the title's style. */
	static byte[] glyph() {
		BufferedImage image = new BufferedImage(GLYPH_SIZE, GLYPH_SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < GLYPH_SIZE; y++) {
			for (int x = 0; x < GLYPH_SIZE; x++) image.setRGB(x, y, 0xFFFFFFFF);
		}
		return PaintArt.png(image);
	}

	/** One of the mod's own post-effect resources, as shipped in the pack. */
	public static byte[] resource(String name) {
		return RivalsPack.bytes("/rivals_post/" + name, name);
	}
}
