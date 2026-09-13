package nu.metacraft.rivals.gun;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import nu.metacraft.rivals.PaintColor;

import java.util.Optional;

/**
 * The weapon picker: one slot per {@link Weapon}, on a 9×1 chest row.
 *
 * <p>Each icon is the <em>real</em> weapon stack, dyed in the viewer's own team colour the way a weapon
 * in their hand is — Polymer draws the model, so the row is four paint guns rather than four stand-in
 * vanilla items. Clicking one takes every paint weapon out of the inventory and puts the chosen one in
 * the first slot, and remembers the pick in {@link WeaponChoice}, so a match start hands out the same
 * weapon and a relog does not lose it.
 *
 * <p>Opened by {@code /rivals weapons} (any player: picking your own weapon is not an admin act) and by
 * right-clicking the {@link WeaponSelector} the lobby hands out.
 */
public final class WeaponMenu extends SimpleGui {
	/** Where the picked weapon goes: the first hotbar slot, so it is in hand a keypress later. */
	public static final int GIVEN_SLOT = 0;

	private WeaponMenu(ServerPlayer player) {
		super(MenuType.GENERIC_9x1, player, false);
	}

	public static void open(ServerPlayer player) {
		WeaponMenu gui = new WeaponMenu(player);
		gui.build();
		gui.open();
	}

	/**
	 * Built (slots filled, title set) but never opened — for game tests, which can inspect
	 * {@link #getSlotRedirect} and {@link #getGuiElement} without a client on the other end of an open
	 * screen.
	 */
	public static WeaponMenu forTest(ServerPlayer player) {
		WeaponMenu gui = new WeaponMenu(player);
		gui.build();
		return gui;
	}

	private void build() {
		Optional<PaintColor> team = PaintColor.byTeam(player.getTeam());
		setTitle(Component.literal("Pick your weapon")
				.withStyle(style -> team.map(color -> style.withColor(color.teamColor.textColor())).orElse(style)));
		Weapon chosen = WeaponChoice.of(player.level().getServer()).orDefault(player);
		int slot = 0;
		for (Weapon weapon : Weapon.values()) {
			setSlot(slot++, icon(weapon, weapon == chosen));
		}
	}

	/** One weapon's slot: the weapon itself in the team's colour, its name, and what it is for in a line. */
	private GuiElementBuilder icon(Weapon weapon, boolean current) {
		ItemStack stack = PaintWeapon.withTankColor(new ItemStack(PaintWeapon.of(weapon)), player.getTeam());
		return GuiElementBuilder.from(stack)
				.setName(Component.literal(weapon.displayName)
						.withStyle(current ? ChatFormatting.GOLD : ChatFormatting.WHITE))
				.glow(current)
				.addLoreLine(Component.literal(blurb(weapon)).withStyle(ChatFormatting.GRAY))
				.addLoreLine(Component.literal(current ? "Yours already — click to take another" : "Click to take it")
						.withStyle(ChatFormatting.DARK_GRAY))
				.setCallback((index, type, action, gui) -> pick(player, weapon));
	}

	/**
	 * The one line under a weapon's name: what it is for, how far it reaches and what it costs, off the
	 * README's weapon table. Ink comes from the live tuning rather than the enum's default, so a server
	 * that has retuned a weapon describes the weapon its players are actually holding.
	 */
	public static String blurb(Weapon weapon) {
		WeaponTuning tuning = WeaponTuning.get(weapon);
		int ink = tuning.intValue(WeaponTuning.Param.INK);
		return switch (weapon) {
			case SHOOTER -> "Rapid fire, mid range — " + ink + " ink a shot, one every "
					+ tuning.intValue(WeaponTuning.Param.COOLDOWN) + " ticks";
			case CHARGER -> "Sniper, a charged line up to " + Math.round(tuning.value(WeaponTuning.Param.RANGE_FULL))
					+ " blocks — " + tuning.intValue(WeaponTuning.Param.CHARGE_INK_MIN) + " to "
					+ (tuning.intValue(WeaponTuning.Param.CHARGE_INK_MIN) + tuning.intValue(WeaponTuning.Param.CHARGE_INK_FULL)) + " ink";
			case SLOSHER -> "A lobbed bucketful, short range and a wide splat — " + ink + " ink a throw";
			case ROLLER -> "Ground cover at touching range — " + ink + " ink a flick, 1 every "
					+ tuning.intValue(WeaponTuning.Param.ROLL_INK_EVERY) + " ticks rolling";
		};
	}

	/**
	 * Take the weapon: every paint weapon out of the inventory, this one into the first slot, the pick
	 * remembered. Returns the stack that was given, so a test can read it.
	 *
	 * <p>Sweeping first matters — the point of picking is to be holding one weapon, not to be holding a
	 * fourth — and the sweep is {@link #sweep}, which the lobby uses too.
	 */
	public static ItemStack pick(ServerPlayer player, Weapon weapon) {
		WeaponChoice.of(player.level().getServer()).set(player, weapon);
		sweep(player);
		ItemStack given = PaintWeapon.withTankColor(new ItemStack(PaintWeapon.of(weapon)), player.getTeam());
		Inventory inventory = player.getInventory();
		if (inventory.getItem(GIVEN_SLOT).isEmpty()) {
			inventory.setItem(GIVEN_SLOT, given);
		} else if (!inventory.add(given)) {
			player.drop(given, false, Prediction.SERVER_ONLY);
		}
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.7f, 1.2f);
		player.sendSystemMessage(Component.literal("You picked the " + weapon.displayName + ". "
				+ blurb(weapon)).withStyle(ChatFormatting.AQUA));
		player.closeContainer();
		return given;
	}

	/**
	 * Every paint weapon out of a player's inventory. Used by the picker (so a pick is a swap rather than
	 * a collection) and by the lobby, where nobody carries a gun. Returns how many stacks went.
	 */
	public static int sweep(Player player) {
		Inventory inventory = player.getInventory();
		int taken = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).getItem() instanceof PaintWeapon) {
				inventory.setItem(slot, ItemStack.EMPTY);
				taken++;
			}
		}
		return taken;
	}
}
