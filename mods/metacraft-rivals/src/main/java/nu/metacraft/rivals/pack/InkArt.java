package nu.metacraft.rivals.pack;

import nu.metacraft.rivals.Rivals;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pack side of the ink on the screen (spec: v6 §B): the post effect, its two shaders, and the
 * texture of the data LED the meter is written into.
 *
 * <p>The post effect is called {@code minecraft:end_of_frame}, which is the one hook a server-side mod
 * has on a vanilla client's frame: 26.3's {@code GameRenderer.update} asks for that chain every single
 * frame and drops the request silently when no pack defines it. A pack that does define it therefore
 * gets one full-screen pass per frame, with nothing to trigger and nothing to turn it on.
 *
 * <p>The LED is one model pixel on top of each weapon, standing on the highest point of the gun so
 * nothing occludes it from the side the camera is on, and it is the only thing in either atlas the item
 * pipelines draw whose alpha is {@link #LED_ALPHA}: the item shader spots that, takes the vertex tint
 * as it is and draws it unlit and exact, so the frame the post effect reads carries the number
 * byte-for-byte. {@link nu.metacraft.rivals.gun.InkOnScreen} writes the number; the texture is white,
 * because all the colour comes from the tint.
 */
public final class InkArt {
	/** The LED's texture, in the item atlas because it hangs on an item model. */
	public static final String LED_TEXTURE = "data_led";
	/**
	 * The LED's marker alpha. 246 sits in 245..247, one of the three three-wide bands that no texel of
	 * any texture in the blocks or items atlas lands on at <em>any</em> mip level 0..4 (244 and 248 are
	 * both reachable — burning fire and a jungle door at mip 3 — which is why the shader's window is
	 * tight enough to admit only 245, 246 and 247).
	 */
	public static final int LED_ALPHA = 246;
	/**
	 * The LED texture's size. A 2x2 sprite would be enough for the one flat colour, but a sprite smaller
	 * than 16 forces the whole item atlas's mip level down to fit it, which would cost every other item
	 * its mipmaps; 16x16 of one value costs a few hundred bytes and changes nothing else.
	 */
	public static final int LED_SIZE = 16;
	/** The post effect id vanilla asks for every frame. */
	public static final String POST_EFFECT = "end_of_frame";
	/** The two fragment shaders the chain runs: the LED search, then the ink itself. */
	public static final String PROBE_SHADER = "ink_probe";
	public static final String INK_SHADER = "ink";

	private InkArt() {}

	/** Every file the ink needs: the chain, its two shaders and the LED's texture. */
	public static Map<String, byte[]> packFiles() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("assets/minecraft/post_effect/" + POST_EFFECT + ".json", resource(POST_EFFECT + ".json"));
		for (String shader : new String[] {PROBE_SHADER, INK_SHADER}) {
			files.put("assets/" + Rivals.MOD_ID + "/shaders/post/" + shader + ".fsh", resource(shader + ".fsh"));
		}
		files.put("assets/" + Rivals.MOD_ID + "/textures/item/" + LED_TEXTURE + ".png", led());
		return files;
	}

	/** The LED sprite: white at the marker alpha, every texel, so mipmaps carry the marker unchanged. */
	static byte[] led() {
		BufferedImage image = new BufferedImage(LED_SIZE, LED_SIZE, BufferedImage.TYPE_INT_ARGB);
		int argb = LED_ALPHA << 24 | 0xFFFFFF;
		for (int y = 0; y < LED_SIZE; y++) {
			for (int x = 0; x < LED_SIZE; x++) image.setRGB(x, y, argb);
		}
		return PaintArt.png(image);
	}

	/** One of the mod's own post-effect resources, as shipped in the pack. */
	public static byte[] resource(String name) {
		return RivalsPack.bytes("/rivals_post/" + name, name);
	}
}
