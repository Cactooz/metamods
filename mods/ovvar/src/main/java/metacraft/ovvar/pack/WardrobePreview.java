package metacraft.ovvar.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import metacraft.ovvar.Ovvar;
import metacraft.ovvar.content.Chapter;
import metacraft.ovvar.content.Piece;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.Spot;
import metacraft.ovvar.datagen.Tex;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The wardrobe screen's preview: a picture of the player's own garment, rendered server-side into
 * the resource pack and drawn in the screen's title next to {@link WardrobeArt}'s background.
 *
 * <p>A vanilla client cannot draw an entity inside a chest screen, so the preview is a
 * <em>paper doll</em>: the humanoid model's front faces, cut out of the very equipment-layer
 * textures the client draws the garment with and composited in the very order
 * {@link EquipmentJson#layerTextures} lists them, laid out flat as a standing figure —
 *
 * <pre>
 *   [arm][  torso  ][arm]   the top's front faces, 4 + 8 + 4 skin px wide, 12 tall
 *        [leg][leg]         the trousers' front faces, 4 + 4 wide, 12 tall
 * </pre>
 *
 * The whole figure is drawn whichever half is being shown, so the screen always holds a figure and
 * not half of one; the half being shown is the one carrying the patches (there are no body cells on
 * the trousers — {@link Spot} puts all of {@link Piece#BOTTOM}'s cells on the legs — so nothing of
 * the other half is ever missing from a preview). The wearer's own left limbs are the mirror images
 * the armour model draws, and a left cell's art is pre-mirrored in its texture, so each side is
 * composited from its own set of placements and the left one flipped back.
 *
 * <p>Sizes: the source textures hold {@link Spot#DETAIL} texels per skin pixel and the doll is
 * drawn at 3 screen px per skin pixel, so the assembled figure (32×48 texels) is resampled ×1.5 to
 * 48×72 px, shaded (darker on the viewer's right and on either arm, as if lit from the left),
 * seamed and outlined in the dark of its own cloth, and centred in a {@value #WIDTH}×{@value
 * #HEIGHT} glyph — exactly the preview panel, rows 1-4 and columns 5-8 of the screen plus its 1 px
 * border. One PNG per (chapter, half, combination); the empty combination is the bare garment.
 *
 * <p>Drawing it: the same negative-space trick as the background, one step further along the title.
 * {@link WardrobeArt#backgroundGlyph} leaves the cursor where it started (the title's own x, 8 px
 * into the container), so the preview is {@code [space +}{@value #FORWARD}{@code ][glyph][space
 * −}{@value #ADVANCE}{@code −}{@value #FORWARD}{@code ]} and ordinary text follows unmoved. The
 * vertical offset is the glyph's ascent: a bitmap glyph's top lands at {@code textY + 7 − ascent}
 * and a container's title is drawn at y 6, so the ascent is negative — it pushes the glyph down
 * into the panel.
 *
 * <p>Budget: {@value #MAX_GLYPHS} glyphs. Chapters × combinations grows quickly, so the codepoints
 * (a private-use range) are allocated in a fixed order — the bare garments first, then by
 * combination id and chapter — and the count is logged on every pack build. Anything past the
 * budget, and any combination the player's own pack does not hold yet, falls back to the bare
 * garment rather than a missing-glyph box.
 */
public final class WardrobePreview {
	private WardrobePreview() {}

	/** The preview panel: rows 1-4, columns 5-8 of a {@code GENERIC_9x6} screen, plus its 1 px border. */
	public static final int WIDTH = 64, HEIGHT = 72;
	/** Where the glyph's top-left corner goes, in container pixels (the panel is 72 px wide; the figure is centred in it). */
	public static final int PANEL_X = 101, PANEL_Y = 35;
	/** A container title is drawn at this x and y inside the container. */
	private static final int TITLE_X = 8, TITLE_Y = 6;

	/** The space that walks the cursor from the title's x to the glyph's, and the one that walks it back. */
	public static final int FORWARD = PANEL_X - TITLE_X;
	/** A bitmap glyph advances by its width plus one (hence {@link Tex#reachingRightEdge}). */
	public static final int ADVANCE = WIDTH + 1;
	public static final int BACK = -(FORWARD + ADVANCE);
	/** {@code top = textY + 7 − ascent}: negative, because the panel is below the title. */
	public static final int ASCENT = TITLE_Y + 7 - PANEL_Y;

	public static final char FORWARD_CHAR = 'd', BACK_CHAR = 'e';
	/** Codepoints for the previews: a private-use range, one per (chapter, half, combination). */
	private static final char FIRST_GLYPH = '\uE000';
	public static final int MAX_GLYPHS = 256;

	private static final String TEXTURE_DIR = "assets/" + Ovvar.MOD_ID + "/textures/wardrobe/preview/";
	private static final String TEXTURE_REF = Ovvar.MOD_ID + ":wardrobe/preview/";

	// ---- the figure's geometry, in texels of the 128x64 equipment layer textures

	private static final int D = Spot.DETAIL;
	/** The side faces of every box are rows 20-32 of the skin layout: 12 skin px tall. */
	private static final int FACE_V = 20 * D, FACE_H = 12 * D;
	/** Front faces: the body's at skin u 20, the right arm's at 44, the right leg's at 4. */
	private static final int TORSO_U = 20 * D, ARM_U = 44 * D, LEG_U = 4 * D;
	private static final int TORSO_W = 8 * D, LIMB_W = 4 * D;
	/** 3 screen px per skin px: {@link Spot#DETAIL} texels become 3. */
	private static final int PX = 3;

	// ---- and the figure's geometry on screen, in the finished glyph's own px

	/** A part's size on screen: a limb 4 skin px across, the torso 8, every face 12 tall. */
	private static final int ARM = LIMB_W / D * PX, TORSO = TORSO_W / D * PX, FACE = FACE_H / D * PX;
	/**
	 * Transparent px between the parts. Without it a front view of arms hanging at the sides is one
	 * slab of cloth 16 skin px wide with an outline round the outside only, which reads as a texture
	 * strip; with it every part gets its own outline and the figure reads as arms, body and legs.
	 */
	private static final int GAP = 1;
	private static final int OUT_W = 2 * ARM + 2 * GAP + TORSO, OUT_H = 2 * FACE;
	private static final int TORSO_X = ARM + GAP, LEGS_Y = FACE;
	private static final int FIGURE_X = (WIDTH - OUT_W) / 2, FIGURE_Y = (HEIGHT - OUT_H) / 2;

	/** How far towards black the viewer's right half of the figure goes, as if lit from the left. */
	private static final double SHADE = 0.15;
	/** A limb is rounded away from the viewer: this much darker again than the torso beside it. */
	private static final double LIMB_SHADE = 0.12;
	/** How far towards black the silhouette's outline goes — the panel's cloth is the chapter colour too. */
	private static final double OUTLINE = 0.78;
	/** And the seams inside the figure (arm against torso, the waist, between the legs). */
	private static final double SEAM = 0.5;

	// ---- the glyph registry

	/** A preview: one chapter's half with one combination of patches sewn on ({@code ""} = nothing). */
	public record Key(Chapter chapter, Piece piece, String combo) implements Comparable<Key> {
		private static final Comparator<Key> ORDER = Comparator.comparing(Key::combo).thenComparing(Key::chapter).thenComparing(Key::piece);

		public boolean bare() {
			return combo.isEmpty();
		}

		public Key bareKey() {
			return new Key(chapter, piece, "");
		}

		@Override
		public int compareTo(Key other) {
			return ORDER.compare(this, other);
		}
	}

	/** Codepoint per preview, never reassigned: a glyph in a pack a player already has must keep its meaning. */
	private static final Map<Key, Character> CHARS = new ConcurrentHashMap<>();
	/** What the last pack build actually wrote (the budget may have cut the tail off). */
	private static volatile Set<Key> written = Set.of();
	/** Pack path → the PNG's size, for the tests: what went into the pack, without unzipping it. */
	private static volatile Map<String, int[]> sizes = Map.of();

	public static char glyphChar(Key key) {
		Character c = CHARS.get(key);
		if (c == null) throw new IllegalStateException("no preview glyph for " + key);
		return c;
	}

	/** Has the last pack build got art for this preview? */
	public static boolean has(Key key) {
		return written.contains(key);
	}

	public static Set<Key> built() {
		return written;
	}

	public static Map<String, int[]> builtSizes() {
		return sizes;
	}

	public static String texturePath(Key key) {
		return TEXTURE_DIR + name(key) + ".png";
	}

	private static String name(Key key) {
		// The codepoint, not the combination id: a combination id is up to 33 "spot.patch" pairs long,
		// well past what a file name may be.
		return Integer.toHexString(glyphChar(key));
	}

	// ---- what the screen draws

	public static Key key(Chapter chapter, Piece piece, List<Placement> placements) {
		return new Key(chapter, piece, Placement.combo(placements).key());
	}

	/**
	 * The preview the player should be shown for this design: their own combination's, or the bare
	 * garment's when the pack <em>they</em> have does not hold it yet (the same generation rule the
	 * garment itself follows, {@link Combos#isBuilt}) or the glyph budget ran out.
	 */
	public static Key shown(Chapter chapter, Piece piece, List<Placement> placements, @Nullable UUID player) {
		Key key = key(chapter, piece, placements);
		if (key.bare()) return key;
		if (!has(key)) return key.bareKey();
		if (!Combos.isBuilt(piece, Placement.combo(placements), player)) return key.bareKey();
		return key;
	}

	/**
	 * {@code [space to the panel][the preview glyph][space back]}, in {@link WardrobeArt#STYLE}, with
	 * the cursor left exactly where it started — empty before the first pack build, when there is no
	 * art to point at yet.
	 */
	public static Component glyph(Key key) {
		if (!has(key)) return Component.empty();
		return Component.literal("" + FORWARD_CHAR + glyphChar(key) + BACK_CHAR).withStyle(WardrobeArt.STYLE);
	}

	// ---- building the art

	/** Every preview the pack should hold: the bare garments, then one per (chapter, known combination). */
	public static List<Key> wanted() {
		List<Key> keys = new ArrayList<>();
		for (Chapter chapter : Chapter.values()) {
			for (Piece piece : Piece.values()) keys.add(new Key(chapter, piece, ""));
		}
		for (Combos.KeyedCombo combo : Combos.known()) {
			if (combo.combo().isEmpty()) continue;
			for (Chapter chapter : Chapter.values()) keys.add(new Key(chapter, combo.piece(), combo.combo().key()));
		}
		keys.sort(null);
		return keys;
	}

	/** Called from {@link WardrobeArt}'s pack-build hook, before the font JSON is written. */
	static void build(ResourcePackBuilder builder) {
		List<Key> keys = wanted();
		Map<String, Tex> cache = new LinkedHashMap<>();
		List<Key> made = new ArrayList<>();
		Map<String, int[]> written = new LinkedHashMap<>();
		int skipped = 0;
		for (Key key : keys) {
			if (!CHARS.containsKey(key)) {
				if (CHARS.size() >= MAX_GLYPHS) { skipped++; continue; }
				CHARS.put(key, (char) (FIRST_GLYPH + CHARS.size()));
			}
			Tex art = art(key, cache);
			builder.addData(texturePath(key), art.png());
			written.put(texturePath(key), new int[]{art.width, art.height});
			made.add(key);
		}
		WardrobePreview.written = Set.copyOf(made);
		WardrobePreview.sizes = Map.copyOf(written);
		Ovvar.LOGGER.info("[ovvar] wardrobe previews: {} glyph(s) of {} ({} ovve(s) × half × combination){}",
				made.size(), MAX_GLYPHS, Chapter.values().length, skipped == 0 ? "" : ", " + skipped + " past the budget (they show the bare ovve)");
	}

	/** The {@code bitmap} providers for {@code assets/ovvar/font/wardrobe.json}, one per preview. */
	static List<JsonObject> providers() {
		List<JsonObject> out = new ArrayList<>();
		for (Key key : wanted()) {
			if (!has(key)) continue;
			JsonObject bitmap = new JsonObject();
			bitmap.addProperty("type", "bitmap");
			bitmap.addProperty("file", TEXTURE_REF + name(key) + ".png");
			bitmap.addProperty("ascent", ASCENT);
			bitmap.addProperty("height", HEIGHT);
			JsonArray chars = new JsonArray();
			chars.add(String.valueOf(glyphChar(key)));
			bitmap.add("chars", chars);
			out.add(bitmap);
		}
		return out;
	}

	// ---- the paper doll

	public static Tex art(Chapter chapter, Piece piece, List<Placement> placements) {
		return art(new Key(chapter, piece, Placement.combo(placements).key()), placements, new LinkedHashMap<>());
	}

	private static Tex art(Key key, Map<String, Tex> cache) {
		return art(key, placements(key), cache);
	}

	private static List<Placement> placements(Key key) {
		if (key.bare()) return List.of();
		return Combos.Combo.KEY_CODEC.parse(com.mojang.serialization.JavaOps.INSTANCE, key.combo())
				.getOrThrow(IllegalStateException::new).placements();
	}

	private static Tex art(Key key, List<Placement> placements, Map<String, Tex> cache) {
		Tex figure = outlined(waisted(shaded(figure(key.chapter(), key.piece(), placements, cache))));
		return Tex.blank(WIDTH, HEIGHT).blit(figure, 0, 0, OUT_W, OUT_H, FIGURE_X, FIGURE_Y).reachingRightEdge();
	}

	/**
	 * The front faces of the humanoid model, cut out and laid out flat at screen size: arms and
	 * torso in the top row of faces, the two legs under the torso, a {@value #GAP} px gap between
	 * the parts.
	 */
	private static Tex figure(Chapter chapter, Piece piece, List<Placement> placements, Map<String, Tex> cache) {
		// The armour model mirrors the wearer's left limbs off the right limbs' strips, and a left
		// cell's art is mirrored in its own texture to suit that, so each side gets its own composite.
		Tex right = body(chapter, piece, placements, Spot.Side.LEFT, cache);
		Tex left = body(chapter, piece, placements, Spot.Side.RIGHT, cache);
		// The wearer's right side is on the viewer's left, and their left limbs are flipped back.
		Tex armR = face(right, ARM_U, LIMB_W, false), armL = face(left, ARM_U, LIMB_W, true);
		Tex legR = face(right, LEG_U, LIMB_W, false), legL = face(left, LEG_U, LIMB_W, true);
		Tex torso = face(right, TORSO_U, TORSO_W, false);
		return Tex.blank(OUT_W, OUT_H)
				.blit(armR, 0, 0, ARM, FACE, 0, 0)
				.blit(torso, 0, 0, TORSO, FACE, TORSO_X, 0)
				.blit(armL, 0, 0, ARM, FACE, TORSO_X + TORSO + GAP, 0)
				.blit(legR, 0, 0, ARM, FACE, TORSO_X, LEGS_Y)
				.blit(legL, 0, 0, ARM, FACE, TORSO_X + ARM + GAP, LEGS_Y);
	}

	/** One front face of a box, at screen size: the textures' {@link Spot#DETAIL} texels per skin px resampled to {@value #PX}. */
	private static Tex face(Tex body, int u, int w, boolean mirror) {
		Tex face = body.crop(u, FACE_V, w, FACE_H);
		return (mirror ? face.flipX() : face).resampled(w / D * PX, FACE);
	}

	/**
	 * Both halves' layer textures composited as the client stacks them — the trousers, then the top
	 * over them — with the shown half's placements on, minus the cells of the side being dropped.
	 */
	private static Tex body(Chapter chapter, Piece piece, List<Placement> placements, Spot.Side drop, Map<String, Tex> cache) {
		List<Placement> kept = placements.stream().filter(p -> p.spot().side != drop).toList();
		Tex top = stack(Piece.TOP, EquipmentJson.layerTextures(chapter, Piece.TOP, false, piece == Piece.TOP ? kept : List.of()), cache);
		Tex bottom = stack(Piece.BOTTOM, EquipmentJson.layerTextures(chapter, Piece.BOTTOM, false, piece == Piece.BOTTOM ? kept : List.of()), cache);
		return bottom.composite(top);
	}

	private static Tex stack(Piece piece, List<String> textures, Map<String, Tex> cache) {
		Tex out = null;
		for (String texture : textures) {
			Tex layer = read(piece, texture, cache);
			out = out == null ? layer : out.composite(layer);
		}
		if (out == null) throw new IllegalStateException("no layers for the " + piece);
		return out;
	}

	/** One equipment layer texture, as datagen wrote it into the jar. */
	private static Tex read(Piece piece, String texture, Map<String, Tex> cache) {
		String path = "assets/" + Ovvar.MOD_ID + "/textures/entity/equipment/" + piece.layer + "/"
				+ texture.substring(texture.indexOf(':') + 1) + ".png";
		return cache.computeIfAbsent(path, p -> {
			try (InputStream in = WardrobePreview.class.getResourceAsStream("/" + p)) {
				if (in == null) throw new IOException("missing " + p + " — run ./gradlew runDatagen");
				return Tex.read(in);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	/** A cell's side in the finished glyph's px. */
	public static final int CELL = Spot.SIZE * PX;

	/**
	 * Where a cell's art lands in the finished glyph, as its top-left px, or null when a front view
	 * does not show that cell at all — the outer, inner and back faces of every box, and the seat,
	 * are round the other side of the figure. The compositor does not use this (it moves whole
	 * faces, not cells); it is here so that what is claimed about the layout can be sampled.
	 */
	public static int @Nullable [] cellAt(Spot spot) {
		int y = (spot.v - 20) * PX;
		if (spot.side == Spot.Side.SEAT) return null;
		if (spot.side == Spot.Side.BODY) {
			if (spot.u < 20 || spot.u >= 20 + 8) return null;   // a side or the back of the body box
			return glyph(TORSO_X + (spot.u - 20) * PX, y);
		}
		boolean arm = spot.piece == Piece.TOP;
		int front = arm ? 44 : 4;                              // the front face of the arm strip, of the leg strip
		if (spot.u < front || spot.u >= front + 4) return null;
		int local = (spot.u - front) * PX;
		if (!arm) y += LEGS_Y;
		if (spot.side == Spot.Side.RIGHT) return glyph((arm ? 0 : TORSO_X) + local, y);
		// The wearer's left limb is the mirror image, on the viewer's right.
		int x0 = arm ? TORSO_X + TORSO + GAP : TORSO_X + ARM + GAP;
		return glyph(x0 + ARM - local - CELL, y);
	}

	private static int[] glyph(int x, int y) {
		return new int[]{FIGURE_X + x, FIGURE_Y + y};
	}

	// ---- the passes that turn flat faces into something with a front and a side to it

	/**
	 * Lit from the viewer's left: the right half of the figure goes {@value #SHADE} towards black,
	 * and either arm {@value #LIMB_SHADE} further — a limb is a narrow box turning away from the
	 * viewer, and without it the shoulders and the torso read as one flat slab of cloth.
	 */
	private static Tex shaded(Tex figure) {
		int[] px = figure.pixels();
		for (int y = 0; y < figure.height; y++) {
			for (int x = 0; x < figure.width; x++) {
				int i = y * figure.width + x;
				boolean arm = y < LEGS_Y && (x < TORSO_X || x >= TORSO_X + TORSO);
				double dark = (x >= figure.width / 2 ? SHADE : 0) + (arm ? LIMB_SHADE : 0);
				if (dark > 0) px[i] = darker(px[i], dark);
			}
		}
		return Tex.of(figure.width, figure.height, px);
	}

	/** The one seam the gaps between the parts do not draw: the waist, where the trousers meet the top. */
	private static Tex waisted(Tex figure) {
		Tex out = figure;
		for (int x = TORSO_X; x < TORSO_X + TORSO; x++) {
			int p = out.get(x, LEGS_Y - 1);
			if (Tex.a(p) != 0) out = out.with(x, LEGS_Y - 1, darker(p, SEAM));
		}
		return out;
	}

	/** A 1 px dark edge along the silhouette, drawn on the figure's own outermost pixels so it costs no room. */
	private static Tex outlined(Tex figure) {
		int[] px = figure.pixels();
		int[] out = px.clone();
		int w = figure.width, h = figure.height;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (Tex.a(px[i]) == 0) continue;
				boolean edge = x == 0 || x == w - 1 || y == 0 || y == h - 1
						|| Tex.a(px[i - 1]) == 0 || Tex.a(px[i + 1]) == 0 || Tex.a(px[i - w]) == 0 || Tex.a(px[i + w]) == 0;
				if (edge) out[i] = darker(px[i], OUTLINE);
			}
		}
		return Tex.of(w, h, out);
	}

	/** {@code towards} of the way to black, alpha kept. */
	private static int darker(int argb, double towards) {
		return Tex.a(argb) == 0 ? argb : Tex.mix(argb, 0xFF000000, towards);
	}
}
