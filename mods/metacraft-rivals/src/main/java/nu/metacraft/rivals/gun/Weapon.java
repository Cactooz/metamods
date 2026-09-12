package nu.metacraft.rivals.gun;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The four weapons, and the numbers that separate them. One {@link PaintWeapon} item is registered
 * per value, at {@code metacraft-rivals:<id>}; the shooter keeps v2's {@code paint_gun} id so its
 * model, item definition and any stack already in a world carry over untouched.
 *
 * <p>{@code velocity} and {@code inaccuracy} feed {@code shootFromRotation}; the charger fires a
 * hitscan line instead of a projectile, so both are 0 for it and its {@code inkPerShot} is only the
 * base cost, with the charge surcharge added at release.
 */
public enum Weapon {
	SHOOTER("paint_gun", "Paint Gun", 1, 4, 1.8f, 2.0f, -2.5f),
	SPRAYER("sprayer", "Paint Sprayer", 1, 4, 0.9f, 9.0f, -1.0f),
	CHARGER("charger", "Paint Charger", 4, 20, 0.0f, 0.0f, -6.0f),
	SLOSHER("slosher", "Paint Slosher", 15, 14, 1.1f, 0.0f, -3.0f);

	/** Registry path and model path. Also accepted by {@code /rivals gun <weapon>}. */
	public final String id;
	public final String displayName;
	public final int inkPerShot;
	public final int cooldownTicks;
	public final float velocity;
	public final float inaccuracy;
	/** Relative pitch nudge on the shot, in degrees; negative is up. */
	public final float kickPitch;

	Weapon(String id, String displayName, int inkPerShot, int cooldownTicks, float velocity, float inaccuracy, float kickPitch) {
		this.id = id;
		this.displayName = displayName;
		this.inkPerShot = inkPerShot;
		this.cooldownTicks = cooldownTicks;
		this.velocity = velocity;
		this.inaccuracy = inaccuracy;
		this.kickPitch = kickPitch;
	}

	/**
	 * What {@code /rivals gun} offers and what the help lists: the weapon's own name, lowercased. It is
	 * the registry id for three of the four, and for the shooter it is {@code shooter} rather than the
	 * v2 registry id {@code paint_gun} — nobody should have to type the latter to get the former.
	 */
	public String commandId() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Either name a weapon answers to: its {@link #commandId} or its registry {@link #id}. */
	public static Optional<Weapon> byId(String id) {
		for (Weapon weapon : values()) {
			if (weapon.id.equals(id) || weapon.commandId().equals(id)) return Optional.of(weapon);
		}
		return Optional.empty();
	}

	/** The names players type, comma-separated, for command help and failure messages. */
	public static String idList() {
		return Stream.of(values()).map(Weapon::commandId).collect(Collectors.joining(", "));
	}
}
