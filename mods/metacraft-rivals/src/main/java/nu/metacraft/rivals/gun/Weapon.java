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
 * <p>Every number here is a <em>default</em>: {@link WeaponTuning} is built from them at startup and
 * the fire modes read it, not these fields, so {@code /rivals tune} can move any of them for the rest
 * of the session without a restart. Change one here and you have changed what a fresh config, and
 * {@code /rivals tune <weapon> reset}, go back to.
 *
 * <p>{@code velocity} and {@code inaccuracy} feed {@code shootFromRotation}; the charger fires a
 * hitscan line instead of a projectile, so both are 0 for it and its {@code inkPerShot} is only the
 * base cost, with the charge surcharge added at release. {@code damage} is the same story: a direct
 * hit on someone from another team hurts, and the charger's share of that is on the charge rather
 * than on a ball.
 */
public enum Weapon {
	SHOOTER("paint_gun", "Paint Gun", 1, 3, 1.8f, 2.0f, -2.5f, 3.0f),
	CHARGER("charger", "Paint Charger", 4, 20, 0.0f, 0.0f, -6.0f, 0.0f),
	SLOSHER("slosher", "Paint Slosher", 15, 14, 1.1f, 0.0f, -3.0f, 4.0f),
	ROLLER("roller", "Paint Roller", 9, 15, 0.55f, 0.0f, -4.0f, 30.0f);

	/** Registry path and model path. Also accepted by {@code /rivals gun <weapon>}. */
	public final String id;
	public final String displayName;
	public final int inkPerShot;
	public final int cooldownTicks;
	public final float velocity;
	public final float inaccuracy;
	/** Relative pitch nudge on the shot, in degrees; negative is up. */
	public final float kickPitch;
	/**
	 * Hearts off a direct hit on someone from another team, per projectile. The slosher throws four and
	 * the roller's flick three, so a face full of either is worth rather more than the number here. The
	 * charger is 0 because it fires no projectile: its damage rides the charge, {@link #CHARGE_BASE_DAMAGE}
	 * plus {@link #CHARGE_EXTRA_DAMAGE}, and is dealt by the hitscan at release.
	 */
	public final float damage;

	// Everything else that separates one weapon from the next. They live here rather than on the item so
	// that one weapon is one place to look, instead of half its numbers being in PaintWeapon; each is the
	// default behind the WeaponTuning parameter of the same meaning, named in its javadoc.

	/**
	 * Shooter: how many block hits reflect the ball instead of ending it. Two rather than one — a single
	 * bounce reads as a ball that stuck to the second wall it met, two as a ball that is bouncing.
	 */
	public static final int SHOOTER_BOUNCES = 2;

	/**
	 * Roller: the flick. Splatoon's Splat Roller swing throws three drops in a near-vertical arc that
	 * lands a few blocks ahead, which is a fan of three at {@link #ROLLER_FAN_YAW} degrees of yaw thrown
	 * {@link #ROLLER_PITCH} degrees above the crosshair at the enum's own low {@code velocity}: the
	 * pitch and the speed together are what makes it an arc rather than a shot.
	 * ({@code splat_roller.json}: 3 projectiles at speed 0.55, startup 6, recovery 15.)
	 */
	public static final int ROLLER_FLICK_BALLS = 3;
	public static final float ROLLER_FAN_YAW = 20.0f;
	public static final float ROLLER_PITCH = -67.0f;
	public static final double ROLLER_GRAVITY = 0.06;
	/** Roller: the flick lands as a bucketful, 5x5 on the face it finds. */
	public static final int ROLLER_SPLAT_RADIUS = 2;
	/**
	 * Roller: how long a right click may be held and still count as a tap rather than a roll. Splatoon
	 * separates the two by the button's own semantics; a vanilla client only sends a hold, so the release
	 * has to tell them apart, and six ticks is about as long as a click lasts.
	 */
	public static final int ROLLER_FLICK_TAP_TICKS = 6;

	// The roll, from splat_roller.json's rolling half and RollerItem.weaponUseTick (Splatcraft, MIT).

	/** Roller: how wide the rolled strip is, in cells. Splatcraft rolls 3 wide. */
	public static final int ROLL_WIDTH = 3;
	/**
	 * Roller: what running someone over is worth. Splatcraft's roll does 25 on contact, which on a 20 HP
	 * scale is most of a player — a roller that reaches you has earned it.
	 */
	public static final float ROLL_DAMAGE = 25.0f;
	/** Roller: ticks before the same victim can be run over again, so a roll is a hit and not a grinder. */
	public static final int ROLL_HIT_COOLDOWN = 10;
	/**
	 * Roller: one ink every this many ticks of rolling. Splatcraft spends 0.06 of a 100-unit tank a tick,
	 * which is a unit every sixteen and change; sixteen is that, in whole ink.
	 */
	public static final int ROLL_INK_EVERY = 16;
	/** Roller: the movement bonus while rolling. Splatcraft's roll mobility is 1.08. */
	public static final double ROLL_SPEED_BONUS = 0.08;
	/** Roller: how far in front of the feet the head sweeps, in blocks. */
	public static final double ROLL_REACH = 1.5;

	/** Slosher: yaw offsets of the fan, degrees from the look direction. */
	public static final float[] SLOSHER_FAN = {-15.0f, -5.0f, 5.0f, 15.0f};
	/** Slosher: it lobs, so it aims above the crosshair and falls harder than a shooter's ball. */
	public static final float SLOSHER_PITCH = -20.0f;
	public static final double SLOSHER_GRAVITY = 0.06;
	/** Slosher: 5x5 on impact. */
	public static final int SLOSHER_SPLAT_RADIUS = 2;

	/** Charger: it is held to charge, so vanilla's cap for "as long as you like". */
	public static final int CHARGE_MAX_TICKS = 72000;
	/** Charger: a full charge, in ticks held; holding longer adds nothing. */
	public static final int CHARGE_FULL_TICKS = 20;
	/** Charger: below this the release is a tap, not a shot — no line, no ink, no cooldown. */
	public static final int MIN_CHARGE_TICKS = 5;
	/** Charger: ink at no charge, and what a full charge adds on top. */
	public static final int CHARGE_BASE_COST = 4;
	public static final int CHARGE_EXTRA_COST = 8;
	/** Charger: hitscan reach in blocks, at no charge and what a full charge adds. */
	public static final double CHARGE_BASE_RANGE = 10.0;
	public static final double CHARGE_EXTRA_RANGE = 30.0;
	/** Charger: hearts off whoever stops the line, at no charge and what a full charge adds. */
	public static final float CHARGE_BASE_DAMAGE = 4.0f;
	public static final float CHARGE_EXTRA_DAMAGE = 6.0f;

	/**
	 * The splat bomb: the special every weapon but the charger throws on a left click. A slow lob that
	 * arms nothing and asks for no aim — it splashes a wide patch of paint where it lands and hurts
	 * whoever is standing in it — bought with most of a tank and a four-second wait of its own, so it is
	 * a decision rather than a second trigger — most of a 40-ink tank, but not all of it, so a bomb still
	 * leaves something to shoot with. The charger's left click is its shot instead; scoping is
	 * what its right click does.
	 */
	public static final int SPECIAL_INK = 25;
	public static final int SPECIAL_COOLDOWN = 80;
	/** How far the splash reaches: 3 is 7×7 on the face it lands on. */
	public static final int SPECIAL_RADIUS = 3;
	/** Hearts off everyone from another team within {@link #SPECIAL_BLAST} blocks of the landing. */
	public static final float SPECIAL_DAMAGE = 6.0f;
	public static final double SPECIAL_BLAST = 2.0;
	/** A slow, heavy lob that gives everyone time to see it coming, and dies on its own after 2 s. */
	public static final float SPECIAL_VELOCITY = 0.8f;
	public static final double SPECIAL_GRAVITY = 0.06;
	public static final int SPECIAL_LIFETIME = 40;
	/** The bomb is a big blob: this is its display scale outright, not a multiple of a ball's. */
	public static final float SPECIAL_SCALE = 1.6f;
	/** It is a lob, so it leaves above the crosshair, in degrees of pitch; negative is up. */
	public static final float SPECIAL_PITCH = -15.0f;

	/** What a bounce droplet is worth — a graze, not a shot. */
	public static final float DROPLET_DAMAGE = 0.5f;

	Weapon(String id, String displayName, int inkPerShot, int cooldownTicks, float velocity, float inaccuracy,
			float kickPitch, float damage) {
		this.id = id;
		this.displayName = displayName;
		this.inkPerShot = inkPerShot;
		this.cooldownTicks = cooldownTicks;
		this.velocity = velocity;
		this.inaccuracy = inaccuracy;
		this.kickPitch = kickPitch;
		this.damage = damage;
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
