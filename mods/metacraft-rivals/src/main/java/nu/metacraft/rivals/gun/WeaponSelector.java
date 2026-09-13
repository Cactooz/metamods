package nu.metacraft.rivals.gun;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import nu.metacraft.rivals.Rivals;

import java.util.List;

/**
 * The lobby's weapon selector: a Polymer item the client is shown as a compass, which opens
 * {@link WeaponDialog} when it is right-clicked.
 *
 * <p>An item rather than only a command, because a player who has just joined a lobby has not read the
 * commands and a thing in the hotbar asks to be clicked. A compass because it already reads as "point
 * me at something". Polymer sends the item's own id as the client's {@code item_model}, so the pack ships
 * {@code items/weapon_selector.json}: a still compass face (vanilla's {@code compass_16}), because a
 * compass that points somewhere spins its needle while it works out where — without that file the
 * client drew the missing-texture square.
 *
 * <p>The name is on the stack ({@link DataComponents#ITEM_NAME}) rather than left to the lang file: what
 * reaches the client is a compass, and a compass is called Compass unless the stack says otherwise.
 */
public final class WeaponSelector extends Item implements PolymerItem {
	public static final Identifier ID = Rivals.id("weapon_selector");
	public static final Component NAME = Component.literal("Weapon selector").withStyle(ChatFormatting.AQUA);

	private static WeaponSelector item;

	private WeaponSelector(Properties properties) {
		super(properties);
	}

	public static void register() {
		item = Registry.register(BuiltInRegistries.ITEM, ID, new WeaponSelector(
				new Properties().stacksTo(1).setId(ResourceKey.create(Registries.ITEM, ID))));
	}

	/** The registered item; null until {@link #register()} has run. */
	public static WeaponSelector get() {
		return item;
	}

	/** One selector, named. What the lobby hands out. */
	public static ItemStack stack() {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.ITEM_NAME, NAME);
		return stack;
	}

	public static boolean is(ItemStack stack) {
		return stack.getItem() instanceof WeaponSelector;
	}

	/** Does this player already carry one? What keeps the lobby from handing out a second. */
	public static boolean carried(Player player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (is(player.getInventory().getItem(slot))) return true;
		}
		return false;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.CONSUME;
		WeaponDialog.open(serverPlayer);
		// The selector's slot is the one place a locked hotbar may go, and it is a door rather than a room:
		// the screen is open, so the hand goes back to the weapon and the player is holding a gun again the
		// moment they have picked one. Nothing to put back for anyone who is not carrying a picked weapon.
		if (WeaponLock.locked(serverPlayer)) WeaponLock.pin(serverPlayer);
		return InteractionResult.SUCCESS_SERVER;
	}

	@Override
	public Item getPolymerItem(ItemStack stack, PacketContext context) {
		return Items.COMPASS;
	}

	@Override
	public void modifyClientTooltip(List<Component> tooltip, ItemStack stack, PacketContext context) {
		tooltip.add(Component.literal("Right click to pick your weapon").withStyle(ChatFormatting.GRAY));
	}

	@Override
	public ItemStack getPolymerItemStack(ItemStack stack, TooltipFlag flag, PacketContext context,
			net.minecraft.core.HolderLookup.Provider lookup) {
		ItemStack client = PolymerItem.super.getPolymerItemStack(stack, flag, context, lookup);
		// A compass points at a lodestone or at spawn and its needle spins while it works that out; this one
		// is a button, so tell the client it is tracking nothing at all.
		client.remove(DataComponents.LODESTONE_TRACKER);
		client.set(DataComponents.ITEM_NAME, NAME);
		return client;
	}
}
