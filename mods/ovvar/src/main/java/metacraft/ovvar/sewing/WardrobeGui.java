package metacraft.ovvar.sewing;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import metacraft.ovvar.OvvarConfig;
import metacraft.ovvar.content.Chapter;
import metacraft.ovvar.content.ModContent;
import metacraft.ovvar.content.OvveItem;
import metacraft.ovvar.content.Patches;
import metacraft.ovvar.content.Piece;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.Spot;
import metacraft.ovvar.content.SpotPlacements;
import metacraft.ovvar.pack.WardrobeArt;
import metacraft.ovvar.store.OwnedSewing;
import metacraft.ovvar.store.Stash;
import metacraft.ovvar.store.StashConfig;
import metacraft.ovvar.store.Wardrobe;
import metacraft.ovvar.store.Wardrobes;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /ovvar stash} (replaces the old plain-chest StashGui): a font-drawn "swag-i-skogen" wardrobe, one
 * {@code GENERIC_9x6} chest whose background is the player's chapter tinted onto
 * {@link WardrobeArt}'s template and drawn as the container title in the {@code ovvar:wardrobe}
 * font, the same negative-space-plus-bitmap-glyph trick as the sewing dialog
 * ({@link SewingFont}) and better-pets' {@code pet_gui}.
 *
 * <p>Grid ({@code slot = row * 9 + col}):
 * <ul>
 *   <li>row 0: one tab per chapter the player owns an ovve of, then a {@code top} and a
 *	   {@code feet} toggle for which {@link Piece} the collection and preview below show
 *	   ({@code feet} is {@link Piece#BOTTOM} — the legs and waist, read informally as "feet"
 *	   since the boots-channel preview lives there; see the README);</li>
 *   <li>rows 1-4, cols 0-4: the patch collection, one slot per stashed patch kind, left/right
 *	   click exactly as the old StashGui did (take out / start a sewing session);</li>
 *   <li>rows 1-4, cols 5-8: the preview — a hover-only item (no callback) at the slot nearest
 *	   each sewn placement's spot, see {@link #previewSlot};</li>
 *   <li>row 5: deposit (col 1), show on mannequin (col 3), finish sewing (col 4, only mid-session),
 *	   help (col 7), close (col 8); cols 0 and 2 are reminder icons for the collection slots' own
 *	   take-out/sew-session gesture (there is no "selected patch" to act on otherwise — see the
 *	   README's Wardrobe screen section).</li>
 * </ul>
 * On a minigame server the screen is look-only: the take-out and sew reminders are gone, and
 * "show on mannequin" is refused (the help book says so); deposit still shows when patches are
 * banked automatically here, same as before, and help/close are always there.
 */
public final class WardrobeGui extends SimpleGui {
	private static final int ROWS = 6, WIDTH = 9;
	private static final int TAB_ROW = 0, BODY_TOP = 1, BODY_ROWS = 4, ACTION_ROW = 5;
	private static final int PATCH_COL0 = 0, PATCH_COLS = 5;
	private static final int PREVIEW_COL0 = 5, PREVIEW_COLS = 4;
	private static final int TAKE_OUT_HINT = slot(ACTION_ROW, 0), DEPOSIT = slot(ACTION_ROW, 1), SEW_HINT = slot(ACTION_ROW, 2);
	private static final int MANNEQUIN = slot(ACTION_ROW, 3), FINISH_SEWING = slot(ACTION_ROW, 4);
	private static final int HELP = slot(ACTION_ROW, 7), CLOSE = slot(ACTION_ROW, 8);

	private final Chapter chapter;
	private final Piece piece;

	public static void open(ServerPlayer player) {
		Chapter chapter = defaultChapter(player);
		WardrobeGui gui = new WardrobeGui(player, chapter, Piece.TOP);
		gui.build();
		gui.open();
	}

	private WardrobeGui(ServerPlayer player, Chapter chapter, Piece piece) {
		super(MenuType.GENERIC_9x6, player, false);
		this.chapter = chapter;
		this.piece = piece;
	}

	/**
	 * Built (slots filled, title set) but never {@link #open() opened} on the player's screen —
	 * for gametests to inspect {@link #getGuiElement} without the networking an open screen needs.
	 */
	public static WardrobeGui forTest(ServerPlayer player, Chapter chapter, Piece piece) {
		WardrobeGui gui = new WardrobeGui(player, chapter, piece);
		gui.build();
		return gui;
	}

	private static int slot(int row, int col) {
		return row * WIDTH + col;
	}

	private static StashConfig config() {
		return OvvarConfig.get().stash();
	}

	// ---- tabs: which chapters the player owns an ovve of

	/** Does the player own (not merely hold) an ovve of this chapter — the tab-visibility rule. */
	public static boolean ownsChapter(ServerPlayer player, Chapter chapter) {
		OvveItem item = ModContent.ovve(chapter);
		return player.getInventory().contains(stack -> stack.getItem() == item && player.getUUID().equals(OvveItem.owner(stack)));
	}

	public static List<Chapter> ownedChapters(ServerPlayer player) {
		List<Chapter> out = new ArrayList<>();
		for (Chapter chapter : Chapter.values()) if (ownsChapter(player, chapter)) out.add(chapter);
		return out;
	}

	private static Chapter defaultChapter(ServerPlayer player) {
		List<Chapter> owned = ownedChapters(player);
		return owned.isEmpty() ? Chapter.values()[0] : owned.get(0);
	}

	// ---- preview: spot -> nearest slot in the 4x4 preview grid

	private record Cell(int row, int col) {}

	/**
	 * Every {@link Spot} of {@link Piece#TOP} (chest, back, both sleeves) or {@link Piece#BOTTOM}
	 * (both legs, the seat) mapped onto the 4x4 preview grid as a front view of the wearer: column
	 * 0 is their left, column 3 their right, the two middle columns the body; row 0 is uppermost.
	 * Several spots share a cell on purpose (the preview has 16 slots for up to 33 spots) — the
	 * last placement drawn to a cell is the one whose tooltip shows, so a design that uses only
	 * one spot per cell (the common case) always shows correctly, and a denser design at least
	 * shows something for every cell it touches.
	 */
	private static final Map<Spot, Cell> PREVIEW_CELL = new EnumMap<>(Spot.class);

	static {
		// Piece.TOP: chest/back in the middle columns, sleeves on the wearer's own left/right.
		PREVIEW_CELL.put(Spot.FRONT_TOP_LEFT, new Cell(0, 1));
		PREVIEW_CELL.put(Spot.FRONT_TOP_RIGHT, new Cell(0, 2));
		PREVIEW_CELL.put(Spot.FRONT_LOW_LEFT, new Cell(1, 1));
		PREVIEW_CELL.put(Spot.FRONT_LOW_RIGHT, new Cell(1, 2));
		PREVIEW_CELL.put(Spot.BACK_TOP_LEFT, new Cell(2, 1));
		PREVIEW_CELL.put(Spot.BACK_TOP_RIGHT, new Cell(2, 2));
		PREVIEW_CELL.put(Spot.BACK_LOW_LEFT, new Cell(3, 1));
		PREVIEW_CELL.put(Spot.BACK_LOW_RIGHT, new Cell(3, 2));
		for (Spot s : List.of(Spot.SLEEVE_OUT_TOP_L, Spot.SLEEVE_FRONT_TOP_L, Spot.SLEEVE_BACK_TOP_L)) PREVIEW_CELL.put(s, new Cell(0, 0));
		for (Spot s : List.of(Spot.SLEEVE_OUT_MID_L, Spot.SLEEVE_FRONT_MID_L, Spot.SLEEVE_BACK_MID_L)) PREVIEW_CELL.put(s, new Cell(1, 0));
		for (Spot s : List.of(Spot.SLEEVE_OUT_TOP_R, Spot.SLEEVE_FRONT_TOP_R, Spot.SLEEVE_BACK_TOP_R)) PREVIEW_CELL.put(s, new Cell(0, 3));
		for (Spot s : List.of(Spot.SLEEVE_OUT_MID_R, Spot.SLEEVE_FRONT_MID_R, Spot.SLEEVE_BACK_MID_R)) PREVIEW_CELL.put(s, new Cell(1, 3));
		// Piece.BOTTOM: legs on the wearer's own left/right, front rows above back rows; the seat in the middle.
		for (Spot s : List.of(Spot.LEG_OUT_TOP_L, Spot.LEG_FRONT_TOP_L)) PREVIEW_CELL.put(s, new Cell(0, 0));
		for (Spot s : List.of(Spot.LEG_OUT_MID_L, Spot.LEG_FRONT_MID_L)) PREVIEW_CELL.put(s, new Cell(1, 0));
		for (Spot s : List.of(Spot.LEG_OUT_TOP_R, Spot.LEG_FRONT_TOP_R)) PREVIEW_CELL.put(s, new Cell(0, 3));
		for (Spot s : List.of(Spot.LEG_OUT_MID_R, Spot.LEG_FRONT_MID_R)) PREVIEW_CELL.put(s, new Cell(1, 3));
		PREVIEW_CELL.put(Spot.LEG_BACK_TOP_L, new Cell(2, 1));
		PREVIEW_CELL.put(Spot.LEG_BACK_MID_L, new Cell(3, 1));
		PREVIEW_CELL.put(Spot.LEG_BACK_TOP_R, new Cell(2, 2));
		PREVIEW_CELL.put(Spot.LEG_BACK_MID_R, new Cell(3, 2));
		PREVIEW_CELL.put(Spot.SEAT, new Cell(2, 1));
	}

	/** The preview slot a placement on {@code spot} is drawn at, or -1 if {@code spot} has none (should not happen). */
	public static int previewSlot(Spot spot) {
		Cell cell = PREVIEW_CELL.get(spot);
		if (cell == null) return -1;
		return slot(BODY_TOP + cell.row(), PREVIEW_COL0 + cell.col());
	}

	// ---- the title: the chapter's background, then the stats strip

	/** {@code earned = stash + every sewn placement} (from the wardrobe record; there is no separate history). */
	public static int earned(Wardrobe wardrobe) {
		int sewn = 0;
		for (SpotPlacements placements : wardrobe.designs().values()) sewn += placements.asPlacementList().size();
		return wardrobe.stashSize() + sewn;
	}

	/** Placements sewn on this chapter's {@code piece} only — the collection/preview below are one piece at a time, so the stat matches what is shown. */
	public static int sewnCount(Wardrobe wardrobe, Chapter chapter, Piece piece) {
		return wardrobe.design(chapter).flatMap(p -> p.forPiece(piece)).map(p -> p.asPlacementList().size()).orElse(0);
	}

	/** {@code [background glyph][stats text]}, per {@code docs/superpowers/specs/2026-09-12-ovvar-wardrobe-screen-design.md} §3. */
	public static Component title(Chapter chapter, Wardrobe wardrobe, Piece piece) {
		String stats = "earned " + earned(wardrobe) + " · sewn " + sewnCount(wardrobe, chapter, piece) + " · stash " + wardrobe.stashSize();
		MutableComponent text = Component.literal(" " + chapter.name + " " + (piece == Piece.TOP ? "top" : "feet") + " · " + stats).withStyle(ChatFormatting.WHITE);
		return Component.empty().append(WardrobeArt.backgroundGlyph(chapter)).append(text);
	}

	// ---- building the screen

	private void build() {
		for (int i = 0; i < ROWS * WIDTH; i++) clearSlot(i);
		if (!Wardrobes.loaded(player.getUUID())) {
			Wardrobes.fetch(player.getUUID());
			setTitle(WardrobeArt.backgroundGlyph(chapter));
			setSlot(slot(BODY_TOP + 1, PATCH_COL0 + 2), new GuiElementBuilder(Items.CLOCK)
					.setName(Component.literal("Loading your wardrobe…").withStyle(ChatFormatting.YELLOW))
					.addLoreLine(Component.literal("Close and open again in a moment").withStyle(ChatFormatting.GRAY)).build());
			setSlot(CLOSE, closeButton());
			return;
		}
		Wardrobe wardrobe = Wardrobes.current(player.getUUID());
		setTitle(title(chapter, wardrobe, piece));

		buildTabs(player);
		buildCollection(player, wardrobe);
		buildPreview(wardrobe);
		buildActions(player, wardrobe);
	}

	private void buildTabs(ServerPlayer player) {
		List<Chapter> owned = ownedChapters(player);
		int col = 0;
		for (Chapter tab : owned) {
			boolean current = tab == chapter;
			GuiElementBuilder element = GuiElementBuilder.from(new ItemStack(ModContent.ovve(tab)))
					.setName(Component.literal(tab.name).withStyle(current ? ChatFormatting.GOLD : ChatFormatting.WHITE))
					.glow(current);
			element.setCallback((index, type, action, gui) -> {
				if (!isOpen()) return;
				WardrobeGui next = new WardrobeGui(player, tab, piece);
				next.build();
				next.open();
			});
			setSlot(slot(TAB_ROW, col++), element.build());
			if (col >= WIDTH - 2) break;   // leave room for the two piece toggles
		}
		setSlot(slot(TAB_ROW, col), pieceToggle(player, Piece.TOP, "Top", Items.LEATHER_CHESTPLATE));
		setSlot(slot(TAB_ROW, col + 1), pieceToggle(player, Piece.BOTTOM, "Feet", Items.LEATHER_BOOTS));
	}

	private GuiElement pieceToggle(ServerPlayer player, Piece target, String label, net.minecraft.world.item.Item icon) {
		boolean current = target == piece;
		return new GuiElementBuilder(icon).setName(Component.literal(label).withStyle(current ? ChatFormatting.GOLD : ChatFormatting.WHITE))
				.glow(current)
				.addLoreLine(Component.literal(target == Piece.TOP ? "Chest and sleeves" : "Legs, waist and the boots channel").withStyle(ChatFormatting.GRAY))
				.setCallback((index, type, action, gui) -> {
					if (!isOpen()) return;
					WardrobeGui next = new WardrobeGui(player, chapter, target);
					next.build();
					next.open();
				}).build();
	}

	private void buildCollection(ServerPlayer player, Wardrobe wardrobe) {
		String refusal = OwnedSewing.editingRefusal(player);
		boolean canSew = refusal == null && config().sessions(), canTake = refusal == null && config().canWithdraw();
		boolean leftTakes = !config().sessions() || config().stashClick() == StashConfig.StashClick.WITHDRAW;

		List<Patches.Patch> stashed = wardrobe.stashed();
		int capacity = BODY_ROWS * PATCH_COLS;
		// One kind's stack of items, not one item per patch, so "kind" and "collection slot" are the
		// same thing; a wardrobe with more kinds than fit gives up the last slot to say so instead.
		boolean overflow = stashed.size() > capacity;
		int max = overflow ? capacity - 1 : Math.min(stashed.size(), capacity);
		for (int i = 0; i < max; i++) {
			Patches.Patch patch = stashed.get(i);
			int row = i / PATCH_COLS, col = i % PATCH_COLS;
			int count = wardrobe.count(patch);
			GuiElementBuilder element = GuiElementBuilder.from(new ItemStack(ModContent.patchItem(patch), Math.min(count, 64)))
					.setName(Component.literal(patch.name()).withStyle(ChatFormatting.WHITE))
					.addLoreLine(Component.literal(count + " in the stash").withStyle(ChatFormatting.GRAY))
					.addLoreLine(Component.literal(patch.seat() ? "Goes across the seat" : "Goes anywhere on an ovve").withStyle(ChatFormatting.DARK_GRAY));
			String take = "take one out (sew it on a stand, or trade it)", sew = "sew it on your ovve here";
			if (canTake) element.addLoreLine(Component.literal((leftTakes ? "Left" : "Right") + "-click: " + take).withStyle(ChatFormatting.YELLOW));
			if (canSew) element.addLoreLine(Component.literal((leftTakes ? "Right" : "Left") + "-click: " + sew).withStyle(ChatFormatting.YELLOW));
			if (refusal != null) element.addLoreLine(Component.literal(refusal).withStyle(ChatFormatting.RED));
			element.setCallback((index, type, action, gui) -> {
				boolean left = type == ClickType.MOUSE_LEFT, right = type == ClickType.MOUSE_RIGHT;
				boolean wantsTake = leftTakes ? left : right, wantsSew = leftTakes ? right : left;
				if (wantsTake && canTake) {
					Stash.withdraw(player, patch, reply -> {
						player.sendOverlayMessage(Component.literal(reply));
						if (isOpen()) build();
					});
				} else if (wantsSew && canSew) {
					close();
					StashSession.start(player, patch, why -> player.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.RED)));
				}
			});
			setSlot(slot(BODY_TOP + row, PATCH_COL0 + col), element.build());
		}
		if (max == 0 && !overflow) {
			setSlot(slot(BODY_TOP + 1, PATCH_COL0 + 2), new GuiElementBuilder(Items.PAPER)
					.setName(Component.literal("No patches in your stash yet").withStyle(ChatFormatting.GRAY))
					.addLoreLine(Component.literal("Patches you earn land here, on every server").withStyle(ChatFormatting.DARK_GRAY)).build());
		}
		if (overflow) {
			int more = stashed.size() - max;
			int row = capacity / PATCH_COLS - 1, col = PATCH_COLS - 1;
			setSlot(slot(BODY_TOP + row, PATCH_COL0 + col), new GuiElementBuilder(Items.CRAFTING_TABLE)
					.setName(Component.literal("+" + more + " more").withStyle(ChatFormatting.GOLD))
					.addLoreLine(Component.literal("kind(s) not shown here").withStyle(ChatFormatting.GRAY)).build());
		}
	}

	/** Rows 1-4, cols 5-8: a hover-only item (no callback) at the slot nearest each sewn placement's spot. */
	private void buildPreview(Wardrobe wardrobe) {
		SpotPlacements sewn = wardrobe.design(chapter).flatMap(p -> p.forPiece(piece)).orElse(null);
		if (sewn == null) return;
		for (Placement placement : sewn.asPlacementList()) {
			int slot = previewSlot(placement.spot());
			if (slot < 0) continue;
			GuiElementBuilder element = GuiElementBuilder.from(new ItemStack(ModContent.patchItem(placement.patch())))
					.setName(Component.literal(placement.patch().name()).withStyle(ChatFormatting.WHITE))
					.addLoreLine(Component.literal(placement.patch().name() + " on " + placement.spot().label()).withStyle(ChatFormatting.GRAY));
			setSlot(slot, element.build());
		}
	}

	private void buildActions(ServerPlayer player, Wardrobe wardrobe) {
		boolean minigame = config().minigameServer();
		// A minigame server is look-only (spec §2): the take-out/sew reminders are absent entirely,
		// and help() already carries the "Minigame server: look, but sew on a survival server" line.
		if (!minigame) {
			setSlot(TAKE_OUT_HINT, new GuiElementBuilder(Items.HOPPER)
					.setName(Component.literal("Take out").withStyle(config().canWithdraw() ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY))
					.addLoreLine(Component.literal(config().canWithdraw() ? "Click a patch above to take it out" : "Not on this server").withStyle(ChatFormatting.GRAY)).build());
			setSlot(SEW_HINT, new GuiElementBuilder(Items.SHEARS)
					.setName(Component.literal("Sew on a stand").withStyle(config().sessions() ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY))
					.addLoreLine(Component.literal(config().sessions() ? "Click a patch above to start sewing" : "Sessions are off on this server").withStyle(ChatFormatting.GRAY)).build());
		}
		if (!minigame || config().banksOnPickup()) {
			setSlot(DEPOSIT, new GuiElementBuilder(Items.CHEST).setName(Component.literal("Put held patches in").withStyle(ChatFormatting.AQUA))
					.addLoreLine(Component.literal("Every patch item in your inventory goes into the stash").withStyle(ChatFormatting.GRAY))
					.setCallback((index, type, action, gui) -> Stash.deposit(player, reply -> {
						player.sendOverlayMessage(Component.literal(reply));
						if (isOpen()) build();
					})).build());
		}

		ItemStack worn = player.getItemBySlot(EquipmentSlot.LEGS);
		boolean canShow = !minigame && worn.getItem() instanceof OvveItem;
		GuiElementBuilder mannequin = new GuiElementBuilder(Items.ARMOR_STAND)
				.setName(Component.literal("Show on mannequin").withStyle(canShow ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY))
				.addLoreLine(Component.literal(minigame ? "Not on this server" : worn.getItem() instanceof OvveItem
						? "A mannequin wearing your ovve, in front of you" : "Wear an ovve first").withStyle(ChatFormatting.GRAY));
		if (canShow) {
			mannequin.setCallback((index, type, action, gui) -> {
				ItemStack copy = worn.copy();
				String refusal = WardrobeMannequin.show(player, copy);
				if (refusal != null) player.sendSystemMessage(Component.literal(refusal).withStyle(ChatFormatting.RED));
				close();
			});
		}
		setSlot(MANNEQUIN, mannequin.build());

		if (StashSession.of(player) != null) {
			setSlot(FINISH_SEWING, new GuiElementBuilder(Items.BARRIER).setName(Component.literal("Finish sewing").withStyle(ChatFormatting.RED))
					.addLoreLine(Component.literal("Ends the session: the stand goes, your hotbar comes back").withStyle(ChatFormatting.GRAY))
					.setCallback((index, type, action, gui) -> {
						close();
						StashSession.end(player, "Sewing session over");
					}).build());
		}

		setSlot(HELP, help(wardrobe));
		setSlot(CLOSE, closeButton());
	}

	private GuiElement help(Wardrobe wardrobe) {
		GuiElementBuilder book = new GuiElementBuilder(Items.BOOK).setName(Component.literal("Your wardrobe").withStyle(ChatFormatting.GOLD));
		if (config().minigameServer()) {
			book.addLoreLine(Component.literal("Minigame server: look, but sew on a survival server").withStyle(ChatFormatting.RED));
		} else {
			boolean leftTakes = !config().sessions() || config().stashClick() == StashConfig.StashClick.WITHDRAW;
			if (config().canWithdraw()) book.addLoreLine(Component.literal((leftTakes ? "Left" : "Right") + "-click a patch to take it out as an item (trade it!)").withStyle(ChatFormatting.GRAY));
			if (config().sessions()) book.addLoreLine(Component.literal((leftTakes ? "Right" : "Left") + "-click to sew it on your ovve on a private stand").withStyle(ChatFormatting.GRAY));
		}
		book.addLoreLine(Component.literal("The stash and your ovvar follow you to every server").withStyle(ChatFormatting.DARK_GRAY));
		for (Map.Entry<Chapter, SpotPlacements> entry : wardrobe.designs().entrySet()) {
			List<Placement> list = entry.getValue().asPlacementList();
			book.addLoreLine(Component.literal(entry.getKey().name + " " + entry.getKey().garmentWord() + ": " + list.size() + " patch(es)").withStyle(ChatFormatting.WHITE));
		}
		return book.build();
	}

	private GuiElement closeButton() {
		return new GuiElementBuilder(Items.SPRUCE_DOOR).setName(Component.literal("Close").withStyle(ChatFormatting.GRAY))
				.setCallback((index, type, action, gui) -> close()).build();
	}
}
