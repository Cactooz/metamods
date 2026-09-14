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
import metacraft.ovvar.pack.WardrobeFont;
import metacraft.ovvar.pack.WardrobePreview;
import metacraft.ovvar.pack.WardrobePreview.Angle;
import metacraft.ovvar.store.OwnedSewing;
import metacraft.ovvar.store.Stash;
import metacraft.ovvar.store.StashConfig;
import metacraft.ovvar.store.Wardrobe;
import metacraft.ovvar.store.Wardrobes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /ovvar stash} (replaces the old plain-chest StashGui): a font-drawn "swag-i-skogen" wardrobe, one
 * {@code GENERIC_9x6} chest whose background is the player's chapter tinted onto
 * {@link WardrobeArt}'s template and drawn as the container title in the {@code ovvar:wardrobe}
 * font, the same negative-space-plus-bitmap-glyph trick as the sewing dialog
 * ({@link SewingFont}) and better-pets' {@code pet_gui}.
 *
 * <p>Grid ({@code slot = row * 9 + col}):
 * <ul>
 *   <li>row 0: one tab per chapter the player owns an ovve of — the ovve itself, named, the one
 *	   being shown glinting and saying "(showing)" — and at the far right (col
 *	   {@value #PIECE_TOGGLE_COL}) a single toggle naming the half on show and the half a click
 *	   brings up ("Showing: Top — click for Trousers"), not an unexplained chestplate and boots;</li>
 *   <li>rows 1-4, cols 0-4: the patch collection, one slot per stashed patch kind, left/right
 *	   click exactly as the old StashGui did (take out / start a sewing session);</li>
 *   <li>rows 1-4, cols 5-8: the preview — a picture of the player's own garment with their patches
 *	   on it, rendered into the pack by {@link WardrobePreview} and drawn by the title's second
 *	   glyph, with a hover-only item wearing the {@code ovvar:invisible} model at the slot nearest
 *	   each sewn placement's spot ({@link #previewSlot}) so the picture shows through and only the
 *	   "which patch is where" tooltip is left;</li>
 *   <li>row 5: one verb per action, each with a line saying what it does — take out (col 0),
 *	   put held patches in (col 1), sew on a stand (col 2), see it in 3D (col 3), finish sewing
 *	   (col 4, only mid-session), help (col 7), close (col 8). Cols 0 and 2 are reminders for the
 *	   collection slots' own take-out/sew gesture (there is no "selected patch" for a button to act
 *	   on); an action this server refuses is a grey pane named "… (not here)" carrying the
 *	   reason from {@link StashConfig}, never a slot that is simply missing.</li>
 * </ul>
 * Empty states say what would be there: no patches puts "No patches yet" in the collection's
 * middle, nothing sewn on this half puts "Nothing sewn on yet" in the middle of the bare garment.
 * On a minigame server the screen is look-only, and every refused action says so where it is; the
 * help item (col 7) explains the screen top to bottom in five lines.
 */
public final class WardrobeGui extends SimpleGui {
	private static final int ROWS = 6, WIDTH = 9;
	private static final int TAB_ROW = 0, BODY_TOP = 1, BODY_ROWS = 4, ACTION_ROW = 5;
	private static final int PATCH_COL0 = 0, PATCH_COLS = 5;
	private static final int PREVIEW_COL0 = 5, PREVIEW_COLS = 4;
	private static final int TAKE_OUT_HINT = slot(ACTION_ROW, 0), DEPOSIT = slot(ACTION_ROW, 1), SEW_HINT = slot(ACTION_ROW, 2);
	private static final int MANNEQUIN = slot(ACTION_ROW, 3), FINISH_SEWING = slot(ACTION_ROW, 4);
	private static final int HELP = slot(ACTION_ROW, 7), CLOSE = slot(ACTION_ROW, 8);
	/** How many columns of the tab row the chapter tabs may use; the far end turns the preview. */
	public static final int TAB_COLS = 7;
	public static final int ROTATE_LEFT_COL = 7, ROTATE_RIGHT_COL = 8;
	public static final int ROTATE_LEFT = slot(TAB_ROW, ROTATE_LEFT_COL), ROTATE_RIGHT = slot(TAB_ROW, ROTATE_RIGHT_COL);

	private final Chapter chapter;
	/** Which way round the preview is turned; per open screen, front to begin with. */
	private final Angle angle;

	public static void open(ServerPlayer player) {
		WardrobeGui gui = new WardrobeGui(player, defaultChapter(player), Angle.FRONT);
		gui.build();
		gui.open();
	}

	private WardrobeGui(ServerPlayer player, Chapter chapter, Angle angle) {
		super(MenuType.GENERIC_9x6, player, false);
		this.chapter = chapter;
		this.angle = angle;
	}

	/**
	 * Built (slots filled, title set) but never {@link #open() opened} on the player's screen —
	 * for gametests to inspect {@link #getGuiElement} without the networking an open screen needs.
	 */
	public static WardrobeGui forTest(ServerPlayer player, Chapter chapter, Angle angle) {
		WardrobeGui gui = new WardrobeGui(player, chapter, angle);
		gui.build();
		return gui;
	}

	/** Which way the preview is turned right now. */
	public Angle angle() {
		return angle;
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

	// ---- preview: spot -> the slot of the 4x4 preview grid nearest where the doll draws it

	private record Cell(int row, int col) {}

	/**
	 * Every {@link Spot} mapped onto the 4x4 grid of hover-only slots over the preview, laid out as
	 * the paper doll behind them is: the top's cells in rows 0-1 (the sleeves at the outer columns,
	 * the chest and back in the middle two) and the trousers' in rows 2-3, with the wearer's right
	 * on the viewer's left, which is where the front view draws it. Several cells share a slot on
	 * purpose - 16 slots for 33 cells, and a cell's front and back cannot both have one - so the
	 * last placement drawn to a slot is the one whose tooltip shows; a design with one cell per slot
	 * (the common case) always shows correctly, and a denser one still points at every slot it
	 * touches. The picture itself is exact: every patch is drawn where it really sits.
	 */
	private static final Map<Spot, Cell> PREVIEW_CELL = new EnumMap<>(Spot.class);

	static {
		// The top: the chest and the back in the middle columns, rows 0-1.
		PREVIEW_CELL.put(Spot.FRONT_TOP_LEFT, new Cell(0, 1));
		PREVIEW_CELL.put(Spot.FRONT_TOP_RIGHT, new Cell(0, 2));
		PREVIEW_CELL.put(Spot.FRONT_LOW_LEFT, new Cell(1, 1));
		PREVIEW_CELL.put(Spot.FRONT_LOW_RIGHT, new Cell(1, 2));
		PREVIEW_CELL.put(Spot.BACK_TOP_LEFT, new Cell(0, 1));
		PREVIEW_CELL.put(Spot.BACK_TOP_RIGHT, new Cell(0, 2));
		PREVIEW_CELL.put(Spot.BACK_LOW_LEFT, new Cell(1, 1));
		PREVIEW_CELL.put(Spot.BACK_LOW_RIGHT, new Cell(1, 2));
		// The sleeves: the wearer's right arm at column 0, their left at column 3.
		for (Spot s : List.of(Spot.SLEEVE_OUT_TOP_R, Spot.SLEEVE_FRONT_TOP_R, Spot.SLEEVE_BACK_TOP_R)) PREVIEW_CELL.put(s, new Cell(0, 0));
		for (Spot s : List.of(Spot.SLEEVE_OUT_MID_R, Spot.SLEEVE_FRONT_MID_R, Spot.SLEEVE_BACK_MID_R)) PREVIEW_CELL.put(s, new Cell(1, 0));
		for (Spot s : List.of(Spot.SLEEVE_OUT_TOP_L, Spot.SLEEVE_FRONT_TOP_L, Spot.SLEEVE_BACK_TOP_L)) PREVIEW_CELL.put(s, new Cell(0, 3));
		for (Spot s : List.of(Spot.SLEEVE_OUT_MID_L, Spot.SLEEVE_FRONT_MID_L, Spot.SLEEVE_BACK_MID_L)) PREVIEW_CELL.put(s, new Cell(1, 3));
		// The trousers: rows 2-3, the wearer's right leg at column 1 and their left at column 2 -
		// the middle two, which is where the doll hangs its legs.
		for (Spot s : List.of(Spot.LEG_OUT_TOP_R, Spot.LEG_FRONT_TOP_R, Spot.LEG_BACK_TOP_R)) PREVIEW_CELL.put(s, new Cell(2, 1));
		for (Spot s : List.of(Spot.LEG_OUT_MID_R, Spot.LEG_FRONT_MID_R, Spot.LEG_BACK_MID_R)) PREVIEW_CELL.put(s, new Cell(3, 1));
		for (Spot s : List.of(Spot.LEG_OUT_TOP_L, Spot.LEG_FRONT_TOP_L, Spot.LEG_BACK_TOP_L)) PREVIEW_CELL.put(s, new Cell(2, 2));
		for (Spot s : List.of(Spot.LEG_OUT_MID_L, Spot.LEG_FRONT_MID_L, Spot.LEG_BACK_MID_L)) PREVIEW_CELL.put(s, new Cell(3, 2));
		PREVIEW_CELL.put(Spot.SEAT, new Cell(3, 1));
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

	/** Everything sewn on this chapter's garment — the preview shows the whole of it, so the stat counts the whole of it. */
	public static int sewnCount(Wardrobe wardrobe, Chapter chapter) {
		return shownPlacements(wardrobe, chapter).size();
	}

	/** The placements the preview draws: this chapter's, both halves, in sewing order. */
	public static List<Placement> shownPlacements(Wardrobe wardrobe, Chapter chapter) {
		return wardrobe.design(chapter).map(SpotPlacements::asPlacementList).orElse(List.of());
	}

	/**
	 * The title for a player: their own pack's generation decides which previews they can be shown,
	 * and {@code activeTab} is the column of the tab they are on (owned-chapter order, so it is
	 * theirs alone) — the highlight under it cannot be baked into a per-chapter background.
	 */
	public static Component title(Chapter chapter, Wardrobe wardrobe, Angle angle, int activeTab) {
		String stats = "earned " + earned(wardrobe) + " · sewn " + sewnCount(wardrobe, chapter) + " · stash " + wardrobe.stashSize();
		MutableComponent text = Component.literal(" " + chapter.name + " " + chapter.garmentWord() + " · " + stats).withStyle(ChatFormatting.WHITE);
		MutableComponent out = Component.empty().append(WardrobeArt.backgroundGlyph(chapter));
		if (activeTab >= 0 && activeTab < TAB_COLS) {
			out.append(WardrobeFont.drawnAt(WardrobeFont.ACTIVE_TAB, WardrobeFont.cellX(activeTab)));
		}
		List<Placement> sewn = shownPlacements(wardrobe, chapter);
		out.append(WardrobePreview.glyphs(chapter, angle, sewn));
		// The empty states are art across the panel they are about, not an item in the middle of it.
		if (wardrobe.stashed().isEmpty()) out.append(WardrobeFont.drawn(WardrobeFont.NO_PATCHES));
		if (sewn.isEmpty()) out.append(WardrobeFont.drawn(WardrobeFont.NOTHING_SEWN));
		return out.append(text);
	}

	/**
	 * {@code [background glyph][active tab highlight][preview glyph][stats text]}, per
	 * {@code docs/superpowers/specs/2026-09-12-ovvar-wardrobe-screen-design.md} §3 and the paper
	 * doll {@link WardrobePreview} adds to it. Every glyph leaves the cursor where it found it, so
	 * the stats strip reads as ordinary text on the same line.
	 */
	public static Component title(Chapter chapter, Wardrobe wardrobe, Angle angle) {
		return title(chapter, wardrobe, angle, 0);
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
		setTitle(title(chapter, wardrobe, angle, ownedChapters(player).indexOf(chapter)));

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
					.setName(Component.literal(tab.name + " " + tab.garmentWord()).withStyle(current ? ChatFormatting.GOLD : ChatFormatting.WHITE))
					.addLoreLine(Component.literal(current ? "(showing)" : "Click to switch to it").withStyle(current ? ChatFormatting.GOLD : ChatFormatting.GRAY))
					.glow(current);
			element.setCallback((index, type, action, gui) -> reopen(player, tab, angle));
			setSlot(slot(TAB_ROW, col++), element.build());
			if (col >= TAB_COLS) break;   // leave the far end to the piece toggle
		}
		setSlot(ROTATE_LEFT, rotator(player, -1, WardrobeAction.ROTATE_LEFT));
		setSlot(ROTATE_RIGHT, rotator(player, 1, WardrobeAction.ROTATE_RIGHT));
	}

	/**
	 * Opens the screen again on another tab or at another angle. A container's title only travels in
	 * the packet that opens it, and the whole preview is drawn by the title, so turning the figure is
	 * re-opening the screen — which is also how switching tabs has always worked here.
	 */
	private void reopen(ServerPlayer player, Chapter tab, Angle to) {
		if (!isOpen()) return;
		WardrobeGui next = new WardrobeGui(player, tab, to);
		next.build();
		next.open();
	}

	/**
	 * One of the two buttons that turn the preview, at the end of the tab row. Four sides, one step at
	 * a time, and the button names the side it would bring round. The ovve is one figure now, top and
	 * trousers together, so there is nothing left to toggle between — which is what the piece toggle
	 * that used to sit here was for.
	 */
	private GuiElement rotator(ServerPlayer player, int turn, WardrobeAction what) {
		Angle to = angle.turned(turn);
		return GuiElementBuilder.from(icon(what, true))
				.setName(Component.literal(what.verb).withStyle(ChatFormatting.AQUA))
				.addLoreLine(Component.literal("Showing: " + angle.label).withStyle(ChatFormatting.GRAY))
				.addLoreLine(Component.literal("Click for: " + to.label).withStyle(ChatFormatting.DARK_GRAY))
				.setCallback((index, type, action, gui) -> reopen(player, chapter, to)).build();
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
		// An empty stash says so in the glyph layer (WardrobeFont.NO_PATCHES, drawn by the title
		// across the whole pocket): no item here, so there is nothing to hover or mistake for a patch.
		if (overflow) {
			int more = stashed.size() - max;
			int row = capacity / PATCH_COLS - 1, col = PATCH_COLS - 1;
			setSlot(slot(BODY_TOP + row, PATCH_COL0 + col), new GuiElementBuilder(Items.CRAFTING_TABLE)
					.setName(Component.literal("+" + more + " more").withStyle(ChatFormatting.GOLD))
					.addLoreLine(Component.literal("kind(s) not shown here").withStyle(ChatFormatting.GRAY)).build());
		}
	}

	/**
	 * Rows 1-4, cols 5-8: the paper doll is the title's second glyph, drawn behind these slots, so
	 * every slot here carries nothing but a tooltip — an item with the {@code ovvar:invisible} model
	 * (a transparent icon), no callback, at the slot nearest each sewn placement's spot.
	 */
	private void buildPreview(Wardrobe wardrobe) {
		// Only the front view: from the other three sides a slot of this grid is nowhere near the
		// cell it would be about, and a tooltip pointing at the wrong part of the figure is worse than
		// none. Nothing sewn at all says so in the glyph layer (WardrobeFont.NOTHING_SEWN).
		if (angle != Angle.FRONT) return;
		for (Placement placement : shownPlacements(wardrobe, chapter)) {
			int slot = previewSlot(placement.spot());
			if (slot < 0) continue;
			GuiElementBuilder element = GuiElementBuilder.from(invisible())
					.setName(Component.literal(placement.patch().name()).withStyle(ChatFormatting.WHITE))
					.addLoreLine(Component.literal(placement.patch().name() + " on " + placement.spot().label()).withStyle(ChatFormatting.GRAY));
			setSlot(slot, element.build());
		}
	}

	/** A stack that draws nothing: hover and it has a name and lore, look at it and the picture behind shows through. */
	private static ItemStack invisible() {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, INVISIBLE_MODEL);
		return stack;
	}

	private static final Identifier INVISIBLE_MODEL = ModContent.id("invisible");

	/**
	 * Row 5: one verb per action, each with a line saying what it does. An action this server does
	 * not allow is not missing — a slot that is simply gone teaches nobody anything — it is a grey
	 * pane named "\<verb\> (not here)" carrying the reason {@link StashConfig} gives for it.
	 */
	private void buildActions(ServerPlayer player, Wardrobe wardrobe) {
		setSlot(TAKE_OUT_HINT, action(WardrobeAction.TAKE_OUT, config().whyNoWithdraw(), null));
		setSlot(SEW_HINT, action(WardrobeAction.SEW, config().whyNoSessions(), null));
		setSlot(DEPOSIT, action(WardrobeAction.PUT_IN, config().whyNoDeposit(), () -> Stash.deposit(player, reply -> {
			player.sendOverlayMessage(Component.literal(reply));
			if (isOpen()) build();
		})));

		ItemStack worn = player.getItemBySlot(EquipmentSlot.LEGS);
		String noMannequin = config().whyNoMannequin() != null ? config().whyNoMannequin()
				: worn.getItem() instanceof OvveItem ? null : "Wear an ovve first";
		setSlot(MANNEQUIN, action(WardrobeAction.SEE_3D, noMannequin, () -> {
			ItemStack copy = worn.copy();
			String refusal = WardrobeMannequin.show(player, copy);
			if (refusal != null) player.sendSystemMessage(Component.literal(refusal).withStyle(ChatFormatting.RED));
			close();
		}));

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

	/**
	 * An action item: its own icon, the verb, the one line saying what it does, and a click — or,
	 * when {@code why} says it cannot be done here, the same icon dimmed and slashed, that reason in
	 * red, and no click at all. {@code click} null means the verb is a reminder for a gesture that
	 * lives on the collection slots themselves (there is no selected patch for a button to act on).
	 */
	private GuiElement action(WardrobeAction what, @Nullable String why, @Nullable Runnable click) {
		boolean available = why == null;
		GuiElementBuilder element = GuiElementBuilder.from(icon(what, available))
				.setName(Component.literal(available ? what.verb : what.verb + " (not here)")
						.withStyle(available ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY));
		if (!available) element.addLoreLine(Component.literal(why).withStyle(ChatFormatting.RED));
		element.addLoreLine(Component.literal(what.does).withStyle(available ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY));
		if (available && click != null) element.setCallback((index, type, action, gui) -> click.run());
		return element.build();
	}

	/** A blank stack wearing one of our own action models: the icon is the model, not the item. */
	private static ItemStack icon(WardrobeAction what, boolean available) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, what.model(available));
		return stack;
	}

	/** The screen explained top to bottom, in five lines, then what this server allows and what is sewn where. */
	private GuiElement help(Wardrobe wardrobe) {
		GuiElementBuilder book = new GuiElementBuilder(Items.BOOK).setName(Component.literal("What this screen is").withStyle(ChatFormatting.GOLD));
		book.addLoreLine(Component.literal("Top row: an ovve per chapter you own — click one to switch;").withStyle(ChatFormatting.GRAY));
		book.addLoreLine(Component.literal("  the far right switches between the top and the trousers.").withStyle(ChatFormatting.GRAY));
		book.addLoreLine(Component.literal("Left panel: your stash, one slot per kind of patch you own.").withStyle(ChatFormatting.GRAY));
		book.addLoreLine(Component.literal("Right panel: your own ovve as it looks now — hover a slot to").withStyle(ChatFormatting.GRAY));
		book.addLoreLine(Component.literal("  see which patch is sewn where on it.").withStyle(ChatFormatting.GRAY));
		book.addLoreLine(Component.literal("Bottom row: what you can do here, greyed out where you cannot.").withStyle(ChatFormatting.GRAY));
		if (config().minigameServer()) {
			book.addLoreLine(Component.literal(StashConfig.LOOK_ONLY + " — sew on a survival server").withStyle(ChatFormatting.RED));
		} else {
			boolean leftTakes = !config().sessions() || config().stashClick() == StashConfig.StashClick.WITHDRAW;
			if (config().canWithdraw()) book.addLoreLine(Component.literal((leftTakes ? "Left" : "Right") + "-click a patch to take it out as an item (trade it!)").withStyle(ChatFormatting.WHITE));
			if (config().sessions()) book.addLoreLine(Component.literal((leftTakes ? "Right" : "Left") + "-click to sew it on your ovve on a private stand").withStyle(ChatFormatting.WHITE));
		}
		book.addLoreLine(Component.literal("The stash and your ovvar follow you to every server").withStyle(ChatFormatting.DARK_GRAY));
		for (Map.Entry<Chapter, SpotPlacements> entry : wardrobe.designs().entrySet()) {
			List<Placement> list = entry.getValue().asPlacementList();
			book.addLoreLine(Component.literal(entry.getKey().name + " " + entry.getKey().garmentWord() + ": " + list.size() + " patch(es)").withStyle(ChatFormatting.DARK_GRAY));
		}
		return book.build();
	}

	private GuiElement closeButton() {
		return new GuiElementBuilder(Items.SPRUCE_DOOR).setName(Component.literal("Close").withStyle(ChatFormatting.GRAY))
				.setCallback((index, type, action, gui) -> close()).build();
	}
}
