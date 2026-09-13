package nu.metacraft.rivals.gun;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Taking a weapon: what a pick does to an inventory, and the line that describes each weapon.
 *
 * <p>No screen of its own — {@link WeaponDialog} is the screen, and it reaches this through
 * {@code /rivals weapons pick <id>}. Keeping the two apart is what let the picker stop being a chest
 * menu without touching what picking means: the sweep, the slot, the remembered choice.
 */
public final class WeaponPicks {
	/** Where the picked weapon goes: the first hotbar slot, so it is in hand a keypress later. */
	public static final int GIVEN_SLOT = 0;

	private WeaponPicks() {}

	/**
	 * The one line under a weapon's picture: what it is for, how far it reaches and what it costs, off the
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
		intoItsSlot(player, given);
		// Picked from the selector's slot, most of the time: the hand goes back to the gun.
		WeaponLock.pin(player);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.7f, 1.2f);
		player.sendSystemMessage(Component.literal("You picked the " + weapon.displayName + ". "
				+ blurb(weapon)).withStyle(ChatFormatting.AQUA));
		return given;
	}

	/**
	 * The weapon into {@link #GIVEN_SLOT}, and whatever was in that slot somewhere else. The slot is the
	 * one the hotbar is locked to ({@link WeaponLock}), so a weapon that landed anywhere else would be a
	 * weapon its owner could never select — which is what {@code inventory.add} did whenever the slot was
	 * occupied, and after a lobby round it is occupied by the selector, because {@code add} put that in the
	 * first free slot and the first free slot is this one.
	 *
	 * <p>Whatever is moved aside is moved rather than destroyed, and only dropped if there is nowhere at all
	 * to put it: losing the selector is losing the way to another weapon.
	 */
	public static void intoItsSlot(ServerPlayer player, ItemStack given) {
		Inventory inventory = player.getInventory();
		ItemStack was = inventory.getItem(GIVEN_SLOT);
		inventory.setItem(GIVEN_SLOT, given);
		if (!was.isEmpty() && !inventory.add(was)) player.drop(was, false, Prediction.SERVER_ONLY);
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
