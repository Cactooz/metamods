package metacraft.ovvar.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import metacraft.ovvar.Ovvar;
import metacraft.ovvar.datagen.Tex;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code ovvar:wardrobe} font's own furniture: the spaces that put a glyph anywhere on the
 * screen, and the small static glyphs the wardrobe draws over its background — the highlight under
 * the tab being shown, the empty-state notices, and the tiny stats readout in the header.
 *
 * <p>Everything here is drawn in the container's title, which is one line of text the client
 * renders at (8, 6) inside the container, so every position is arithmetic:
 * <ul>
 *   <li><b>across:</b> a {@code space} provider with advances ±1, ±2, … ±128 ({@link #move}) walks
 *	   the cursor to any x and back again, so a glyph can be placed per player — under whichever
 *	   tab is showing, or right-aligned against the header's right margin — which a fixed pair of
 *	   advances could not;</li>
 *   <li><b>down:</b> a bitmap glyph's top lands at {@code textY + 7 − ascent}, so a glyph whose art
 *	   should start at container y {@code top} declares {@link #ascent(int) ascent(top)} —
 *	   negative for anything below the header, which is most of the screen.</li>
 * </ul>
 *
 * <p>The slot grid these positions are quoted in: an item's icon fills the 16×16 at
 * ({@value #ITEM_X} + 18·col, {@value #ITEM_Y} + 18·row) and the 18×18 cell around it — the ring
 * the vanilla slot frame would be drawn on, which the wardrobe's own background hides — starts one
 * pixel up and to the left. Anything drawn on that ring is a frame; anything drawn inside it
 * overlaps the icon, which is exactly what the v2 screen got wrong.
 */
public final class WardrobeFont {
	private WardrobeFont() {}

	/** Where the client draws a container's title, inside the container. */
	public static final int TITLE_X = 8, TITLE_Y = 6;
	/** The chest's slot grid: the icon's own 16×16, the 18 px pitch, and the cell ring one px outside it. */
	public static final int ITEM_X = 8, ITEM_Y = 18, PITCH = 18, ICON = 16;
	public static final int CELL = PITCH;

	/** The x of a slot's 18×18 cell: its frame ring, one px left of the icon. */
	public static int cellX(int col) {
		return ITEM_X - 1 + PITCH * col;
	}

	/** The y of a slot's 18×18 cell. */
	public static int cellY(int row) {
		return ITEM_Y - 1 + PITCH * row;
	}

	/** The ascent a bitmap glyph needs for its art to start at container y {@code top}. */
	public static int ascent(int top) {
		return TITLE_Y + 7 - top;
	}

	// ---- spaces: ±1, ±2, … ±128, so any move up to 255 px is a handful of codepoints

	private static final char SPACE_FIRST = '\uE800';
	private static final int SPACE_BITS = 8;

	/** Codepoint → advance, for the font's {@code space} provider. */
	public static Map<Character, Integer> spaceAdvances() {
		Map<Character, Integer> out = new LinkedHashMap<>();
		for (int bit = 0; bit < SPACE_BITS; bit++) {
			out.put((char) (SPACE_FIRST + bit), 1 << bit);
			out.put((char) (SPACE_FIRST + SPACE_BITS + bit), -(1 << bit));
		}
		return out;
	}

	/** Spaces that move the cursor {@code dx} px, either way. */
	public static String move(int dx) {
		int size = Math.abs(dx);
		if (size >= 1 << SPACE_BITS) throw new IllegalArgumentException("a move of " + dx + " px is too far for the wardrobe font");
		StringBuilder out = new StringBuilder();
		for (int bit = 0; bit < SPACE_BITS; bit++) {
			if ((size >> bit & 1) != 0) out.append((char) (SPACE_FIRST + (dx < 0 ? SPACE_BITS : 0) + bit));
		}
		return out.toString();
	}

	// ---- the static glyphs

	/**
	 * One bitmap glyph of the font: its art, its size, the container y its top sits at (which fixes
	 * its ascent) and the codepoint it is drawn with. {@code art} is called once per pack build.
	 */
	public record Glyph(String name, int width, int height, int top, char codepoint, Supplier<Tex> art) {
		/** What the client adds to the cursor after drawing it: the texture's width plus one. */
		public int advance() {
			return width + 1;
		}

		public String texturePath() {
			return "assets/" + Ovvar.MOD_ID + "/textures/wardrobe/" + name + ".png";
		}

		public String textureRef() {
			return Ovvar.MOD_ID + ":wardrobe/" + name + ".png";
		}
	}

	private static final List<Glyph> GLYPHS = new ArrayList<>();
	/** The static glyphs' codepoints; the previews have {@code \\uE000}… and the spaces {@code \\uE800}…. */
	private static char next = '\uE100';

	static Glyph glyph(String name, int width, int height, int top, Supplier<Tex> art) {
		if (next >= SPACE_FIRST) throw new IllegalStateException("too many wardrobe glyphs");
		Glyph glyph = new Glyph(name, width, height, top, next++, art);
		GLYPHS.add(glyph);
		return glyph;
	}

	public static List<Glyph> glyphs() {
		return List.copyOf(GLYPHS);
	}

	/**
	 * The lighter box under the tab being shown. The background is one glyph per chapter and the
	 * tabs are in owned-chapter order, which is per player, so the highlight cannot be baked into
	 * the background: it is its own cell-sized glyph, placed with spaces at the active tab's slot.
	 */
	public static final Glyph ACTIVE_TAB = glyph("active_tab", CELL, CELL, cellY(0), WardrobeFont::activeTabArt);

	private static Tex activeTabArt() {
		int[] px = new int[CELL * CELL];
		for (int y = 0; y < CELL; y++) {
			for (int x = 0; x < CELL; x++) {
				boolean ring = x == 0 || y == 0 || x == CELL - 1 || y == CELL - 1;
				// The ring: solid white where the background's own stitching is dashed, so the
				// showing tab reads as a raised button. Inside: a wash of white over the cloth.
				px[y * CELL + x] = ring ? 0xFFFFFFFF : 0x38FFFFFF;
			}
		}
		return Tex.of(CELL, CELL, px);
	}

	// ---- drawing

	/** {@code [spaces to x][the glyph][spaces back]}: the cursor ends exactly where it started. */
	public static String at(Glyph glyph, int x) {
		int dx = x - TITLE_X;
		return move(dx) + glyph.codepoint() + move(-(dx + glyph.advance()));
	}

	/** The same, as a styled component ready to append to a title. */
	public static Component drawn(Glyph glyph, int x) {
		return Component.literal(at(glyph, x)).withStyle(WardrobeArt.STYLE);
	}

	// ---- the pack

	/** Writes every static glyph's PNG; called from {@link WardrobeArt}'s pack-build hook. */
	static void build(ResourcePackBuilder builder) {
		for (Glyph glyph : GLYPHS) {
			Tex art = glyph.art().get();
			if (art.width != glyph.width() || art.height != glyph.height()) {
				throw new IllegalStateException(glyph.name() + " is " + art.width + "x" + art.height + ", the font says " + glyph.width() + "x" + glyph.height());
			}
			builder.addData(glyph.texturePath(), art.reachingRightEdge().png());
		}
		Ovvar.LOGGER.info("[ovvar] wardrobe font: {} static glyph(s)", GLYPHS.size());
	}

	static List<JsonObject> providers() {
		List<JsonObject> out = new ArrayList<>();
		for (Glyph glyph : GLYPHS) {
			JsonObject bitmap = new JsonObject();
			bitmap.addProperty("type", "bitmap");
			bitmap.addProperty("file", glyph.textureRef());
			bitmap.addProperty("ascent", ascent(glyph.top()));
			bitmap.addProperty("height", glyph.height());
			JsonArray chars = new JsonArray();
			chars.add(String.valueOf(glyph.codepoint()));
			bitmap.add("chars", chars);
			out.add(bitmap);
		}
		return out;
	}
}
