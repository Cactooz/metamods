package metacraft.ovvar.pack;

import metacraft.ovvar.content.Chapter;
import metacraft.ovvar.content.Patches;
import metacraft.ovvar.content.Piece;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.Spot;
import metacraft.ovvar.datagen.Tex;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A dev tool, not part of the game: the wardrobe screen as a client would draw it — the chapter
 * background, the paper doll exactly where its glyph's space advances and ascent put it, and a
 * marker per slot the screen fills — upscaled so it can be eyeballed without starting a client.
 * {@code ./gradlew :mods:ovvar:wardrobeSheet --offline} (see {@code build.gradle}); the same
 * one-off spirit as {@code tools/wardrobe_template.py}.
 *
 * <p>What it cannot show, being a compositor and not a client: that the client's own font renderer
 * agrees about the advances and the (negative) ascent, and that nothing of the screen's own chrome
 * lands on top of the doll.
 */
public final class WardrobeSheet {
	private WardrobeSheet() {}

	/** Slot geometry of a {@code GENERIC_9x6} screen, in container px. */
	private static final int SLOT0_X = 8, SLOT0_Y = 18, PITCH = 18, SLOT = 16;
	private static final int UPSCALE = 3;
	/** The marker colours: a filled slot the screen sets, and the preview panel's hover-only slots. */
	private static final int ITEM = 0x60FFFFFF, HOVER = 0x40FFD700;

	public static void main(String[] args) throws IOException {
		Path out = Path.of(args.length > 0 ? args[0] : "/tmp/wardrobe_v2_sheet.png");
		Chapter chapter = args.length > 1 ? Chapter.byId(args[1]) : Chapter.DATA;
		Piece piece = args.length > 2 && args[2].equals(Piece.BOTTOM.id) ? Piece.BOTTOM : Piece.TOP;
		List<Placement> sample = piece == Piece.TOP
				? List.of(new Placement(Spot.FRONT_TOP_LEFT, Patches.get("beer")),
						new Placement(Spot.FRONT_LOW_RIGHT, Patches.get("kth")),
						new Placement(Spot.SLEEVE_FRONT_TOP_R, Patches.get("star")),
						new Placement(Spot.SLEEVE_FRONT_MID_L, Patches.get("nolle")))
				: List.of(new Placement(Spot.LEG_FRONT_TOP_R, Patches.get("heart")),
						new Placement(Spot.LEG_FRONT_MID_L, Patches.get("gasque")));

		boolean empty = args.length > 3 && args[3].equals("empty");
		Tex sheet = tex(WardrobeArt.tint(WardrobeArt.readTemplate(), WardrobeArt.colour(chapter)));
		Tex doll = WardrobePreview.art(chapter, piece, empty ? List.of() : sample);
		sheet = over(sheet, doll, WardrobePreview.PANEL_X, WardrobePreview.PANEL_Y);
		if (empty) {
			for (WardrobeFont.Glyph notice : List.of(WardrobeFont.NO_PATCHES, WardrobeFont.NOTHING_SEWN)) {
				sheet = over(sheet, notice.art().get(), notice.x(), notice.top());
			}
		}
		for (int[] slot : slots()) sheet = box(sheet, SLOT0_X + slot[1] * PITCH, SLOT0_Y + slot[0] * PITCH, slot[2]);

		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.write(out, sheet.scale(UPSCALE).png());
		System.out.println("wrote " + out + " (" + sheet.width * UPSCALE + "x" + sheet.height * UPSCALE + ", "
				+ chapter.id + " " + piece.id + ", " + sample.size() + " patches sewn)");
	}

	/** Every slot the screen fills, as {row, col, colour}: the doll must stay readable between them. */
	private static List<int[]> slots() {
		List<int[]> out = new ArrayList<>();
		for (int col = 0; col < 3; col++) out.add(new int[]{0, col, ITEM});              // chapter tabs
		out.add(new int[]{0, 8, ITEM});                                                   // the one piece toggle
		for (int i = 0; i < 3; i++) out.add(new int[]{1 + i / 5, i % 5, ITEM});           // the patch collection
		for (int col = 5; col < 9; col++) for (int row = 1; row < 5; row++) out.add(new int[]{row, col, HOVER});
		for (int col : new int[]{0, 1, 2, 3, 7, 8}) out.add(new int[]{5, col, ITEM});      // the action row
		return out;
	}

	private static Tex tex(BufferedImage image) {
		int w = image.getWidth(), h = image.getHeight();
		return Tex.of(w, h, image.getRGB(0, 0, w, h, null, 0, w));
	}

	private static Tex over(Tex under, Tex top, int x, int y) {
		Tex padded = Tex.blank(under.width, under.height).blit(top, 0, 0, top.width, Math.min(top.height, under.height - y), x, y);
		return under.composite(padded);
	}

	private static Tex box(Tex sheet, int x, int y, int argb) {
		Tex mark = Tex.blank(sheet.width, sheet.height);
		for (int i = 0; i < SLOT; i++) {
			mark = mark.with(x + i, y, argb).with(x + i, y + SLOT - 1, argb).with(x, y + i, argb).with(x + SLOT - 1, y + i, argb);
		}
		return sheet.composite(mark);
	}
}
