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
import metacraft.ovvar.pack.Combos;
import metacraft.ovvar.pack.WardrobeArt;
import metacraft.ovvar.pack.WardrobeFont;
import metacraft.ovvar.pack.WardrobePreview;
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
	/** How many columns of the tab row the chapter tabs may use; the far end is the piece toggle. */
	public static final int TAB_COLS = 8;
	/** The far end of the tab row: the one toggle for which half the screen shows. */
	public static final int PIECE_TOGGLE_COL = 8;
	public static final int PIECE_TOGGLE = slot(TAB_ROW, PIECE_TOGGLE_COL);
	/** The middle of each panel, where an empty one says what would be there. */
	public static final int NO_PATCHES = slot(BODY_TOP + 1, PATCH_COL0 + 2), NOTHING_SEWN = slot(BODY_TOP + 1, PREVIEW_COL0 + 1);

	private final Chapter chapter;
	private final Piece piece;

	/** Whose wardrobe screen is open, so a pack build can re-send its title (the glyphs changed under it). */
	private static final Map<UUID, WardrobeGui> OPEN = new ConcurrentHashMap<>();

	public static void init() {
		// A container's title only travels in the packet that opens it, and the preview is part of
		// the title, so a player whose client has just loaded a pack holding their newest design
		// gets the screen opened again — at once, with the paper doll they were waiting for.
		Combos.onPackLoaded(WardrobeGui::reopen);
	}

	private static void reopen(ServerPlayer player) {
		WardrobeGui gui = OPEN.get(player.getUUID());
		if (gui == null || !gui.isOpen()) return;
		WardrobeGui next = new WardrobeGui(player, gui.chapter, gui.piece);
		next.build();
		next.open();
	}

	@Override
	public void onOpen() {
		OPEN.put(player.getUUID(), this);
	}

	@Override
	public void onRemoved() {
		OPEN.remove(player.getUUID(), this);
	}

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

	/** The placements the preview should draw: this chapter's, on the half being shown. */
	public static List<Placement> shownPlacements(Wardrobe wardrobe, Chapter chapter, Piece piece) {
		return wardrobe.design(chapter).flatMap(p -> p.forPiece(piece)).map(SpotPlacements::asPlacementList).orElse(List.of());
	}

	/** Which paper doll the screen draws — the bare garment when nothing is sewn on this half. */
	public static WardrobePreview.Key previewKey(Chapter chapter, Wardrobe wardrobe, Piece piece, @Nullable UUID player) {
		return WardrobePreview.shown(chapter, piece, shownPlacements(wardrobe, chapter, piece), player);
	}

	/**
	 * The title for a player: their own pack's generation decides which previews they can be shown,
	 * and {@code activeTab} is the column of the tab they are on (owned-chapter order, so it is
	 * theirs alone) — the highlight under it cannot be baked into a per-chapter background.
	 */
	public static Component title(Chapter chapter, Wardrobe wardrobe, Piece piece, @Nullable UUID player, int activeTab) {
		String stats = "earned " + earned(wardrobe) + " · sewn " + sewnCount(wardrobe, chapter, piece) + " · stash " + wardrobe.stashSize();
		MutableComponent text = Component.literal(" " + chapter.name + " " + (piece == Piece.TOP ? "top" : "trousers") + " · " + stats).withStyle(ChatFormatting.WHITE);
		MutableComponent out = Component.empty().append(WardrobeArt.backgroundGlyph(chapter));
		if (activeTab >= 0 && activeTab < TAB_COLS) {
			out.append(WardrobeFont.drawn(WardrobeFont.ACTIVE_TAB, WardrobeFont.cellX(activeTab)));
		}
		return out.append(WardrobePreview.glyph(previewKey(chapter, wardrobe, piece, player))).append(text);
	}

	/**
	 * {@code [background glyph][active tab highlight][preview glyph][stats text]}, per
	 * {@code docs/superpowers/specs/2026-09-12-ovvar-wardrobe-screen-design.md} §3 and the paper
	 * doll {@link WardrobePreview} adds to it. Every glyph leaves the cursor where it found it, so
	 * the stats strip reads as ordinary text on the same line.
	 */
	public static Component title(Chapter chapter, Wardrobe wardrobe, Piece piece) {
		return title(chapter, wardrobe, piece, null, 0);
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
		setTitle(title(chapter, wardrobe, piece, player.getUUID(), ownedChapters(player).indexOf(chapter)));

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
			element.setCallback((index, type, action, gui) -> {
				if (!isOpen()) return;
				WardrobeGui next = new WardrobeGui(player, tab, piece);
				next.build();
				next.open();
			});
			setSlot(slot(TAB_ROW, col++), element.build());
			if (col >= TAB_COLS) break;   // leave the far end to the piece toggle
		}
		if (pieces().size() > 1) setSlot(slot(TAB_ROW, PIECE_TOGGLE_COL), pieceToggle(player));
	}

	/**
	 * The halves the screen can show. A half with no cells at all has nothing to show and would
	 * leave the toggle with nowhere to go; both halves have cells today, so the toggle is always
	 * there, and a garment drawn as one piece would drop it by itself.
	 */
	public static List<Piece> pieces() {
		List<Piece> out = new ArrayList<>();
		for (Piece p : Piece.values()) if (!Spot.cells(p).isEmpty()) out.add(p);
		return out;
	}

	/** What the half being shown is called, in the words a wearer uses. */
	public static String pieceName(Piece piece) {
		return piece == Piece.TOP ? "Top" : "Trousers";
	}

	/**
	 * One toggle, not a chestplate and a pair of boots: it says which half is on show and which one
	 * a click brings up, and its icon is the ovve's own piece (through {@code ITEM_MODEL}, so it is
	 * that garment's art and not a piece of armour).
	 */
	private GuiElement pieceToggle(ServerPlayer player) {
		List<Piece> pieces = pieces();
		Piece other = pieces.get((pieces.indexOf(piece) + 1) % pieces.size());
		ItemStack icon = new ItemStack(piece == Piece.TOP ? Items.LEATHER_CHESTPLATE : Items.LEATHER_BOOTS);
		icon.set(DataComponents.ITEM_MODEL, piece == Piece.TOP ? ModContent.topId(chapter) : ModContent.feetId(chapter));
		return GuiElementBuilder.from(icon)
				.setName(Component.literal("Showing: " + pieceName(piece) + " — click for " + pieceName(other)).withStyle(ChatFormatting.GOLD))
				.addLoreLine(Component.literal(piece == Piece.TOP ? "The chest, back and sleeves" : "The legs, the waist and the seat").withStyle(ChatFormatting.GRAY))
				.setCallback((index, type, action, gui) -> {
					if (!isOpen()) return;
					WardrobeGui next = new WardrobeGui(player, chapter, other);
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
			setSlot(NO_PATCHES, new GuiElementBuilder(Items.PAPER)
					.setName(Component.literal("No patches yet").withStyle(ChatFormatting.GRAY))
					.addLoreLine(Component.literal("Patches are earned at chapter events").withStyle(ChatFormatting.DARK_GRAY))
					.addLoreLine(Component.literal("Gamemasters: /ovvar patch give").withStyle(ChatFormatting.DARK_GRAY))
					.addLoreLine(Component.literal("Whatever you earn lands here, on every server").withStyle(ChatFormatting.DARK_GRAY)).build());
		}
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
		List<Placement> sewn = shownPlacements(wardrobe, chapter, piece);
		// Nothing sewn on this half, and so a bare garment on the doll behind: say so in the middle
		// of it. (A design the player's own pack cannot draw yet also shows the bare garment, but
		// then there are patches to point at and their tooltips are worth more than the hint.)
		if (sewn.isEmpty() && previewKey(chapter, wardrobe, piece, player.getUUID()).bare()) {
			setSlot(NOTHING_SEWN, new GuiElementBuilder(Items.PAPER)
					.setName(Component.literal("Nothing sewn on yet").withStyle(ChatFormatting.GRAY))
					.addLoreLine(Component.literal("Take a patch to a sewing stand and it shows up here").withStyle(ChatFormatting.DARK_GRAY))
					.addLoreLine(Component.literal("This is your " + chapter.name + " " + chapter.garmentWord() + " as it looks now").withStyle(ChatFormatting.DARK_GRAY)).build());
			return;
		}
		for (Placement placement : sewn) {
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
		String noWithdraw = config().whyNoWithdraw(), noSessions = config().whyNoSessions();
		setSlot(TAKE_OUT_HINT, action(Items.HOPPER, "Take out", "Click a patch on the left to take it out as an item", noWithdraw, null));
		setSlot(SEW_HINT, action(Items.SHEARS, "Sew on a stand", "Click a patch on the left to start sewing it on", noSessions, null));
		setSlot(DEPOSIT, action(Items.CHEST, "Put held patches in", "Every patch item in your inventory goes into your stash",
				config().whyNoDeposit(), () -> Stash.deposit(player, reply -> {
					player.sendOverlayMessage(Component.literal(reply));
					if (isOpen()) build();
				})));

		ItemStack worn = player.getItemBySlot(EquipmentSlot.LEGS);
		String noMannequin = config().whyNoMannequin() != null ? config().whyNoMannequin()
				: worn.getItem() instanceof OvveItem ? null : "Wear an ovve first";
		setSlot(MANNEQUIN, action(Items.ARMOR_STAND, "See it in 3D", "Stands a mannequin wearing your ovve in front of you",
				noMannequin, () -> {
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
	 * An action item: the verb, the one line saying what it does, and a click — or, when
	 * {@code why} says it cannot be done here, a grey pane with that reason and no click at all.
	 * {@code click} null means the verb is a reminder for a gesture that lives on the collection
	 * slots themselves (there is no selected patch for a button to act on).
	 */
	private GuiElement action(net.minecraft.world.item.Item icon, String verb, String does, @Nullable String why, @Nullable Runnable click) {
		if (why != null) {
			return new GuiElementBuilder(Items.STAINED_GLASS_PANE.gray())
					.setName(Component.literal(verb + " (not here)").withStyle(ChatFormatting.DARK_GRAY))
					.addLoreLine(Component.literal(why).withStyle(ChatFormatting.RED))
					.addLoreLine(Component.literal(does).withStyle(ChatFormatting.DARK_GRAY)).build();
		}
		GuiElementBuilder element = new GuiElementBuilder(icon)
				.setName(Component.literal(verb).withStyle(ChatFormatting.AQUA))
				.addLoreLine(Component.literal(does).withStyle(ChatFormatting.GRAY));
		if (click != null) element.setCallback((index, type, action, gui) -> click.run());
		return element.build();
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
