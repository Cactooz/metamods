package metacraft.ovvar.sewing;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import metacraft.ovvar.OvvarConfig;
import metacraft.ovvar.content.Chapter;
import metacraft.ovvar.content.ModContent;
import metacraft.ovvar.content.Patches;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.SpotPlacements;
import metacraft.ovvar.store.OwnedSewing;
import metacraft.ovvar.store.Stash;
import metacraft.ovvar.store.StashConfig;
import metacraft.ovvar.store.Wardrobe;
import metacraft.ovvar.store.Wardrobes;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

/**
 * {@code /ovvar stash}: a six-row chest. The top five rows are the patches in the player's stash,
 * one slot per kind with the count; the bottom row explains, shows what is sewn on each ovve, puts
 * held patches in, ends a session, closes. Left-click a patch to start sewing it on the private
 * stand ({@link StashSession}); right-click to take one out as an item where the server allows.
 * On a minigame server the patches are shown and nothing can be clicked.
 */
public final class StashGui extends SimpleGui {
	private static final int ROWS = 6, WIDTH = 9, PATCH_SLOTS = (ROWS - 1) * WIDTH;
	private static final int INFO = 45, DEPOSIT = 47, DONE = 51, CLOSE = 53;

	public static void open(ServerPlayer player) {
		StashGui gui = new StashGui(player);
		gui.build();
		gui.open();
	}

	private StashGui(ServerPlayer player) {
		super(MenuType.GENERIC_9x6, player, false);
		setTitle(Component.literal("Your patch stash"));
	}

	private static StashConfig config() {
		return OvvarConfig.get().stash();
	}

	private void build() {
		for (int i = 0; i < ROWS * WIDTH; i++) clearSlot(i);
		if (!Wardrobes.loaded(player.getUUID())) {
			Wardrobes.fetch(player.getUUID());
			setSlot(22, new GuiElementBuilder(Items.CLOCK).setName(Component.literal("Loading your wardrobe…").withStyle(ChatFormatting.YELLOW))
					.addLoreLine(Component.literal("Close and open again in a moment").withStyle(ChatFormatting.GRAY)).build());
			setSlot(CLOSE, closeButton());
			return;
		}
		Wardrobe wardrobe = Wardrobes.current(player.getUUID());
		String refusal = OwnedSewing.editingRefusal(player);
		boolean canSew = refusal == null && config().sessions(), canTake = refusal == null && config().canWithdraw();
		boolean leftTakes = !config().sessions() || config().stashClick() == StashConfig.StashClick.WITHDRAW;

		int slot = 0;
		for (Patches.Patch patch : wardrobe.stashed()) {
			if (slot >= PATCH_SLOTS) break;
			int count = wardrobe.count(patch);
			GuiElementBuilder element = GuiElementBuilder.from(new ItemStack(ModContent.patchItem(patch), Math.min(count, 64)))
					.setName(Component.literal(patch.name()).withStyle(ChatFormatting.WHITE))
					.addLoreLine(Component.literal(count + " in the stash").withStyle(ChatFormatting.GRAY))
					.addLoreLine(Component.literal(patch.seat() ? "Goes across the seat" : "Goes anywhere on an ovve").withStyle(ChatFormatting.DARK_GRAY));
			if (patch.artist() != null) element.addLoreLine(Component.literal("Art by " + patch.artist()).withStyle(ChatFormatting.GRAY));
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
			setSlot(slot++, element.build());
		}
		if (slot == 0) {
			setSlot(22, new GuiElementBuilder(Items.PAPER).setName(Component.literal("No patches in your stash yet").withStyle(ChatFormatting.GRAY))
					.addLoreLine(Component.literal("Patches you earn land here, on every server").withStyle(ChatFormatting.DARK_GRAY)).build());
		}

		setSlot(INFO, info(wardrobe));
		if (!config().minigameServer() || config().banksOnPickup()) {
			setSlot(DEPOSIT, new GuiElementBuilder(Items.CHEST).setName(Component.literal("Put held patches in").withStyle(ChatFormatting.AQUA))
					.addLoreLine(Component.literal("Every patch item in your inventory goes into the stash").withStyle(ChatFormatting.GRAY))
					.setCallback((index, type, action, gui) -> Stash.deposit(player, reply -> {
						player.sendOverlayMessage(Component.literal(reply));
						if (isOpen()) build();
					})).build());
		}
		if (StashSession.of(player) != null) {
			setSlot(DONE, new GuiElementBuilder(Items.BARRIER).setName(Component.literal("Finish sewing").withStyle(ChatFormatting.RED))
					.addLoreLine(Component.literal("Ends the session: the stand goes, your hotbar comes back").withStyle(ChatFormatting.GRAY))
					.setCallback((index, type, action, gui) -> {
						close();
						StashSession.end(player, "Sewing session over");
					}).build());
		}
		setSlot(CLOSE, closeButton());
	}

	/** The book: what the stash is here, and what is sewn on each of the player's ovvar. */
	private GuiElement info(Wardrobe wardrobe) {
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
