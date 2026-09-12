package nu.metacraft.rivals.gun;

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

	/** Registry path and model path; also what {@code /rivals gun <weapon>} takes. */
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

	public static Optional<Weapon> byId(String id) {
		for (Weapon weapon : values()) {
			if (weapon.id.equals(id)) return Optional.of(weapon);
		}
		return Optional.empty();
	}

	/** The ids, comma-separated, for command help and failure messages. */
	public static String idList() {
		return Stream.of(values()).map(weapon -> weapon.id).collect(Collectors.joining(", "));
	}
}
