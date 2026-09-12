package nu.metacraft.rivals.gun;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.paint.Painter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every number a shot is made of, per weapon, live. One instance per {@link Weapon} holds a value
 * for every {@link Param}; the fire modes read it at the moment they fire, so a value changed with
 * {@code /rivals tune} is in the next shot without a restart.
 *
 * <p>The defaults are the constants the weapons were written with — the {@link Weapon} enum fields,
 * the grouped {@code SLOSHER_*}/{@code CHARGE_*} statics and {@link PaintBall}'s flight constants,
 * read from the code rather than copied, so there is still exactly one place a default lives. Only
 * what has been changed away from them is kept in the file: a tuning nobody has touched is
 * {@code {}}, and a default that is edited in the code moves with it.
 */
public final class WeaponTuning {
	/**
	 * The tunable numbers. The id is what a player types and what the json is keyed by; the rest of the
	 * code uses the constant. Not every parameter applies to every weapon — the charger fires no ball
	 * and the other three take no charge — so {@link #applies} decides which of these a weapon shows.
	 */
	public enum Param {
		/** Launch speed, blocks per tick, into {@code shootFromRotation}. */
		VELOCITY("velocity"),
		/** Random inaccuracy cone, in degrees-ish; vanilla's {@code inaccuracy}. */
		SPREAD("spread"),
		/** Fall rate per tick. A vanilla snowball is 0.03. */
		GRAVITY("gravity"),
		/** How many block hits reflect the ball instead of ending it. Rounded. */
		BOUNCES("bounces"),
		/** How much speed a bounce keeps. */
		RESTITUTION("restitution"),
		/** Ticks before an unhit ball splashes the floor under itself; 0 for no limit. Rounded. */
		LIFETIME("lifetime"),
		/** How far the impact splat reaches: 0 a single face, 1 a 3x3, 2 a 5x5. Rounded. */
		SPLAT_RADIUS("splat_radius"),
		/** Ink one shot costs, and the tank a shot needs before it is allowed. Rounded. */
		INK("ink"),
		/** Ticks of item cooldown after a shot — the fire rate. Rounded. */
		COOLDOWN("cooldown"),
		/** Camera kick, in pitch degrees; negative is up. */
		KICK("kick"),
		/** Hearts off a direct hit on someone from another team, per projectile. */
		DAMAGE("damage"),
		/** Balls thrown per shot. Rounded. */
		COUNT("count"),
		/** Degrees of yaw between neighbouring balls of a multi-ball shot; the fan is centred on the view. */
		FAN_YAW("fan_yaw"),
		/** Degrees of pitch added to the view before the throw; negative aims above the crosshair. */
		FAN_PITCH("fan_pitch"),
		/** Droplets one bounce throws off. Rounded. */
		SPATTER_COUNT("spatter_count"),
		/** Ticks one of those droplets lives. Rounded. */
		SPATTER_LIFETIME("spatter_lifetime"),
		/** The fraction of the reflected speed a droplet leaves at. */
		SPATTER_SPEED("spatter_speed"),
		/** How far a droplet is nudged off that line. */
		SPATTER_SCATTER("spatter_scatter"),
		/** Hearts off a droplet hit — a graze, not a shot. */
		SPATTER_DAMAGE("spatter_damage"),
		/** Charger: ticks held below which the release is a tap, not a shot. Rounded. */
		CHARGE_MIN("charge_min"),
		/** Charger: ticks held for a full charge; holding longer adds nothing. Rounded. */
		CHARGE_FULL("charge_full"),
		/** Charger: hitscan reach in blocks at no charge, and at a full one. */
		RANGE_MIN("range_min"),
		RANGE_FULL("range_full"),
		/** Charger: ink a release costs at no charge, and at a full one. Rounded. */
		CHARGE_INK_MIN("charge_ink_min"),
		CHARGE_INK_FULL("charge_ink_full"),
		/** Charger: hearts off whoever stops the line, at no charge and at a full one. */
		CHARGE_DAMAGE_MIN("charge_damage_min"),
		CHARGE_DAMAGE_FULL("charge_damage_full");

		/** What a player types and what the json is keyed by. */
		public final String id;

		Param(String id) {
			this.id = id;
		}

		public static Optional<Param> byId(String id) {
			String wanted = id.toLowerCase(Locale.ROOT);
			for (Param param : values()) {
				if (param.id.equals(wanted)) return Optional.of(param);
			}
			return Optional.empty();
		}

		/** Every name, comma-separated, for command help and failure messages. */
		public static String idList() {
			return Stream.of(values()).map(param -> param.id).collect(Collectors.joining(", "));
		}
	}

	/** The charger's own, which only it reads. */
	private static final List<Param> CHARGE_ONLY = List.of(Param.CHARGE_MIN, Param.CHARGE_FULL,
			Param.RANGE_MIN, Param.RANGE_FULL, Param.CHARGE_INK_MIN, Param.CHARGE_INK_FULL,
			Param.CHARGE_DAMAGE_MIN, Param.CHARGE_DAMAGE_FULL);

	/**
	 * Does this parameter mean anything for this weapon? The charger throws no ball, so none of the
	 * flight numbers reach it; the other three never charge. Ink, cooldown and kick belong to all four.
	 * Only used for what the commands offer and accept — {@link #value} answers for any of them.
	 */
	public static boolean applies(Weapon weapon, Param param) {
		boolean charge = CHARGE_ONLY.contains(param);
		return weapon == Weapon.CHARGER ? charge || param == Param.INK || param == Param.COOLDOWN || param == Param.KICK : !charge;
	}

	/** The parameters a weapon shows and accepts, in declaration order. */
	public static List<Param> params(Weapon weapon) {
		List<Param> list = new ArrayList<>();
		for (Param param : Param.values()) {
			if (applies(weapon, param)) list.add(param);
		}
		return list;
	}

	/** The names that weapon answers to, comma-separated, for a failure message. */
	public static String paramList(Weapon weapon) {
		return params(weapon).stream().map(param -> param.id).collect(Collectors.joining(", "));
	}

	private static final EnumMap<Weapon, EnumMap<Param, Double>> DEFAULTS = defaults();
	private static final EnumMap<Weapon, WeaponTuning> ACTIVE = new EnumMap<>(Weapon.class);

	static {
		for (Weapon weapon : Weapon.values()) ACTIVE.put(weapon, new WeaponTuning(weapon));
	}

	/**
	 * Today's numbers, read off the constants they were written as. The three ball weapons share one
	 * shape — {@code count} balls, fanned by {@code fan_yaw} around the view and pitched by
	 * {@code fan_pitch} — which the old per-weapon arms of {@code fire} spelled out separately: the
	 * slosher's {@code {-15, -5, 5, 15}} is four balls ten degrees apart, and the shooter's single
	 * ball is that same fan with one in it.
	 */
	private static EnumMap<Weapon, EnumMap<Param, Double>> defaults() {
		EnumMap<Weapon, EnumMap<Param, Double>> all = new EnumMap<>(Weapon.class);
		for (Weapon weapon : Weapon.values()) {
			EnumMap<Param, Double> values = new EnumMap<>(Param.class);
			// Shared by all four, straight off the enum.
			values.put(Param.VELOCITY, (double) weapon.velocity);
			values.put(Param.SPREAD, (double) weapon.inaccuracy);
			values.put(Param.INK, (double) weapon.inkPerShot);
			values.put(Param.COOLDOWN, (double) weapon.cooldownTicks);
			values.put(Param.KICK, (double) weapon.kickPitch);
			values.put(Param.DAMAGE, (double) weapon.damage);
			// The ball's own, from PaintBall's flight constants unless the weapon overrode them.
			values.put(Param.GRAVITY, PaintBall.GRAVITY);
			values.put(Param.BOUNCES, 0.0);
			values.put(Param.RESTITUTION, PaintBall.BOUNCE_RESTITUTION);
			values.put(Param.LIFETIME, 0.0);
			values.put(Param.SPLAT_RADIUS, (double) Painter.RADIUS);
			values.put(Param.COUNT, 1.0);
			values.put(Param.FAN_YAW, 0.0);
			values.put(Param.FAN_PITCH, 0.0);
			values.put(Param.SPATTER_COUNT, (double) PaintBall.BOUNCE_DROPLETS);
			values.put(Param.SPATTER_LIFETIME, (double) PaintBall.DROPLET_LIFETIME);
			values.put(Param.SPATTER_SPEED, PaintBall.DROPLET_SPEED);
			values.put(Param.SPATTER_SCATTER, PaintBall.DROPLET_SCATTER);
			values.put(Param.SPATTER_DAMAGE, (double) Weapon.DROPLET_DAMAGE);
			values.put(Param.CHARGE_MIN, (double) Weapon.MIN_CHARGE_TICKS);
			values.put(Param.CHARGE_FULL, (double) Weapon.CHARGE_FULL_TICKS);
			values.put(Param.RANGE_MIN, Weapon.CHARGE_BASE_RANGE);
			values.put(Param.RANGE_FULL, Weapon.CHARGE_BASE_RANGE + Weapon.CHARGE_EXTRA_RANGE);
			values.put(Param.CHARGE_INK_MIN, (double) Weapon.CHARGE_BASE_COST);
			values.put(Param.CHARGE_INK_FULL, (double) (Weapon.CHARGE_BASE_COST + Weapon.CHARGE_EXTRA_COST));
			values.put(Param.CHARGE_DAMAGE_MIN, (double) Weapon.CHARGE_BASE_DAMAGE);
			values.put(Param.CHARGE_DAMAGE_FULL, (double) (Weapon.CHARGE_BASE_DAMAGE + Weapon.CHARGE_EXTRA_DAMAGE));
			switch (weapon) {
				case SHOOTER -> values.put(Param.BOUNCES, (double) Weapon.SHOOTER_BOUNCES);
				case SPRAYER -> {
					values.put(Param.COUNT, (double) Weapon.SPRAYER_DROPLETS);
					values.put(Param.LIFETIME, (double) Weapon.SPRAYER_LIFETIME);
					values.put(Param.SPLAT_RADIUS, 0.0);
				}
				case SLOSHER -> {
					values.put(Param.COUNT, (double) Weapon.SLOSHER_FAN.length);
					values.put(Param.FAN_YAW, fanStep(Weapon.SLOSHER_FAN));
					values.put(Param.FAN_PITCH, (double) Weapon.SLOSHER_PITCH);
					values.put(Param.GRAVITY, Weapon.SLOSHER_GRAVITY);
					values.put(Param.SPLAT_RADIUS, (double) Weapon.SLOSHER_SPLAT_RADIUS);
				}
				case CHARGER -> {} // it throws nothing; its own numbers are the CHARGE_* above
			}
			all.put(weapon, values);
		}
		return all;
	}

	/** The gap between neighbouring offsets of a hand-written fan, which is the fan as one number. */
	private static double fanStep(float[] fan) {
		return fan.length < 2 ? 0.0 : fan[1] - fan[0];
	}

	private final Weapon weapon;
	private final EnumMap<Param, Double> values;

	private WeaponTuning(Weapon weapon) {
		this.weapon = weapon;
		this.values = new EnumMap<>(DEFAULTS.get(weapon));
	}

	/** The live tuning for a weapon. Never null, and the same instance for the life of the server. */
	public static WeaponTuning get(Weapon weapon) {
		return ACTIVE.get(weapon);
	}

	public Weapon weapon() {
		return weapon;
	}

	public double value(Param param) {
		return values.get(param);
	}

	/** By name, as a player types it. Unknown names throw rather than answer with a default. */
	public double value(String param) {
		return value(Param.byId(param).orElseThrow(() ->
				new IllegalArgumentException("No weapon parameter called \"" + param + "\". Try one of: " + paramList(weapon))));
	}

	/** For the counts and the tick numbers, which are whole even when the file is not. */
	public int intValue(Param param) {
		return (int) Math.round(value(param));
	}

	public float floatValue(Param param) {
		return (float) value(param);
	}

	/** What this parameter was before anybody tuned it. */
	public double defaultValue(Param param) {
		return DEFAULTS.get(weapon).get(param);
	}

	public boolean isDefault(Param param) {
		return value(param) == defaultValue(param);
	}

	/** Every parameter of this weapon that has been moved off its default, in declaration order. */
	public List<Param> changed() {
		List<Param> changed = new ArrayList<>();
		for (Param param : params(weapon)) {
			if (!isDefault(param)) changed.add(param);
		}
		return changed;
	}

	/** Returns what the parameter was, so the caller can report old → new. */
	public double set(Param param, double value) {
		double was = value(param);
		values.put(param, value);
		return was;
	}

	/** This weapon back to the numbers it shipped with. */
	public void reset() {
		values.putAll(DEFAULTS.get(weapon));
	}

	/** Every weapon back to the numbers it shipped with. */
	public static void resetAll() {
		for (Weapon weapon : Weapon.values()) get(weapon).reset();
	}

	/** Is anything tuned at all? What {@code /rivals tune} with no arguments answers. */
	public static boolean allDefault() {
		for (Weapon weapon : Weapon.values()) {
			if (!get(weapon).changed().isEmpty()) return false;
		}
		return true;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** {@code config/metacraft-rivals/weapons.json}, the file the tuning lives in between sessions. */
	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(Rivals.MOD_ID).resolve("weapons.json");
	}

	/**
	 * Write what has been changed, and only that: a weapon with nothing tuned is left out entirely, so
	 * a file nobody has touched is {@code {}} and every default stays the code's to change.
	 */
	public static void save(Path path) {
		JsonObject root = new JsonObject();
		for (Weapon weapon : Weapon.values()) {
			WeaponTuning tuning = get(weapon);
			List<Param> changed = tuning.changed();
			if (changed.isEmpty()) continue;
			JsonObject object = new JsonObject();
			for (Param param : changed) object.addProperty(param.id, tuning.value(param));
			root.add(weapon.commandId(), object);
		}
		try {
			Path parent = path.getParent();
			if (parent != null) Files.createDirectories(parent);
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
		} catch (IOException | RuntimeException failure) {
			Rivals.LOGGER.warn("[{}] could not write {}: {}", Rivals.MOD_ID, path, failure.toString());
		}
	}

	/** The live file. */
	public static void save() {
		save(configPath());
	}

	/**
	 * Read a tuning file over the defaults: every weapon starts from its default and takes whatever the
	 * file names, so a key that has been dropped from the file is a parameter back at its default.
	 * Missing file, unreadable file, unknown weapon or unknown parameter — none of them is worth
	 * refusing to start over, so each is a line in the log and the defaults stand.
	 */
	public static void load(Path path) {
		resetAll();
		if (!Files.isRegularFile(path)) return;
		JsonObject root;
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonElement parsed = JsonParser.parseReader(reader);
			if (!parsed.isJsonObject()) {
				Rivals.LOGGER.warn("[{}] {} is not a json object; weapon tuning stays at the defaults", Rivals.MOD_ID, path);
				return;
			}
			root = parsed.getAsJsonObject();
		} catch (IOException | RuntimeException failure) {
			Rivals.LOGGER.warn("[{}] could not read {} ({}); weapon tuning stays at the defaults",
					Rivals.MOD_ID, path, failure.toString());
			return;
		}
		int taken = 0;
		for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
			Optional<Weapon> weapon = Weapon.byId(entry.getKey());
			if (weapon.isEmpty() || !entry.getValue().isJsonObject()) {
				Rivals.LOGGER.warn("[{}] {}: no weapon called \"{}\", ignored", Rivals.MOD_ID, path, entry.getKey());
				continue;
			}
			WeaponTuning tuning = get(weapon.get());
			for (Map.Entry<String, JsonElement> field : entry.getValue().getAsJsonObject().entrySet()) {
				Optional<Param> param = Param.byId(field.getKey());
				if (param.isEmpty() || !field.getValue().isJsonPrimitive()) {
					Rivals.LOGGER.warn("[{}] {}: {} has no parameter called \"{}\", ignored",
							Rivals.MOD_ID, path, entry.getKey(), field.getKey());
					continue;
				}
				try {
					tuning.set(param.get(), field.getValue().getAsDouble());
					taken++;
				} catch (RuntimeException notANumber) {
					Rivals.LOGGER.warn("[{}] {}: {}.{} is not a number, ignored",
							Rivals.MOD_ID, path, entry.getKey(), field.getKey());
				}
			}
		}
		if (taken > 0) Rivals.LOGGER.info("[{}] weapon tuning: {} values from {}", Rivals.MOD_ID, taken, path);
	}

	/** The live file, read at server start. */
	public static void load() {
		load(configPath());
	}
}
