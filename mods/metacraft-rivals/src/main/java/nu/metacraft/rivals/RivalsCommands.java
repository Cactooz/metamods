package nu.metacraft.rivals;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import nu.metacraft.rivals.gun.PaintWeapon;
import nu.metacraft.rivals.gun.Weapon;
import nu.metacraft.rivals.gun.WeaponTuning;
import nu.metacraft.rivals.gun.WeaponTuning.Param;
import nu.metacraft.rivals.paint.PaintTally;
import nu.metacraft.rivals.paint.Unpaintable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /rivals setup | gun [weapon] | kit | score | reset | reload | tune}, for game masters
 * (permission {@code metacraft.rivals}).
 */
public final class RivalsCommands {
	private RivalsCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				literal("rivals")
						.requires(source -> Permissions.check(source, "metacraft.rivals", PermissionLevel.GAMEMASTERS))
						.then(literal("setup").executes(ctx -> {
							int touched = setupTeams(ctx.getSource().getServer());
							ctx.getSource().sendSuccess(() -> Component.literal("Teams ready: " + PaintColor.idList()
									+ ". Join with /team join <colour> @s"), true);
							return touched;
						}))
						// A plain word rather than a registry or enum argument: the ids are the weapon's own, and an
						// unknown one should say what is on offer instead of failing to parse.
						.then(literal("gun")
								.executes(ctx -> gun(ctx.getSource(), Weapon.SHOOTER))
								.then(argument("weapon", StringArgumentType.word()).executes(ctx -> {
									String id = StringArgumentType.getString(ctx, "weapon");
									Optional<Weapon> weapon = Weapon.byId(id);
									if (weapon.isEmpty()) {
										ctx.getSource().sendFailure(Component.literal("No weapon called \"" + id + "\". Try one of: " + Weapon.idList())
												.withStyle(ChatFormatting.RED));
										return 0;
									}
									return gun(ctx.getSource(), weapon.get());
								})))
						// Everything a shot is made of, live. Weapons and parameters are plain words rather than
						// enum arguments for the same reason /rivals gun is: an unknown one should answer with
						// what is on offer instead of failing to parse, and "reset" sits in the same slot.
						.then(literal("tune")
								.executes(ctx -> tuneAll(ctx.getSource()))
								.then(argument("weapon", StringArgumentType.word()).suggests(WEAPONS)
										.executes(ctx -> tuneWeapon(ctx.getSource(), StringArgumentType.getString(ctx, "weapon")))
										.then(argument("param", StringArgumentType.word()).suggests(PARAMS)
												.executes(ctx -> tuneParam(ctx.getSource(), StringArgumentType.getString(ctx, "weapon"),
														StringArgumentType.getString(ctx, "param")))
												.then(argument("value", DoubleArgumentType.doubleArg())
														.executes(ctx -> tuneSet(ctx.getSource(), StringArgumentType.getString(ctx, "weapon"),
																StringArgumentType.getString(ctx, "param"),
																DoubleArgumentType.getDouble(ctx, "value")))))))
						.then(literal("kit").executes(ctx -> kit(ctx.getSource())))
						.then(literal("score").executes(ctx -> score(ctx.getSource())))
						.then(literal("reset").executes(ctx -> reset(ctx.getSource())))
						.then(literal("reload").executes(ctx -> reload(ctx.getSource())))));
	}

	/** Create or update one vanilla team per colour. Returns the number of teams touched. */
	public static int setupTeams(MinecraftServer server) {
		ServerScoreboard board = server.getScoreboard();
		int touched = 0;
		for (PaintColor color : PaintColor.values()) {
			PlayerTeam team = board.getPlayerTeam(color.id);
			if (team == null) team = board.addPlayerTeam(color.id);
			team.setDisplayName(Component.literal(color.displayName));
			team.setColor(Optional.of(color.teamColor));
			team.setAllowFriendlyFire(false);
			team.setCollisionRule(Team.CollisionRule.NEVER);
			touched++;
		}
		return touched;
	}

	private static int gun(CommandSourceStack source, Weapon weapon) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ItemStack gun = new ItemStack(PaintWeapon.of(weapon));
		if (!player.getInventory().add(gun)) player.drop(gun, false, Prediction.SERVER_ONLY);
		source.sendSuccess(() -> Component.literal("Here is a " + weapon.displayName + ". Right-click to fire; join a team for colour."), false);
		return 1;
	}

	private static int kit(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		int given = PaintWeapon.giveKit(player);
		source.sendSuccess(() -> Component.literal("Here is the full kit: " + Weapon.idList()), false);
		return given;
	}

	private static int score(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		Map<PaintColor, Integer> counts = PaintTally.of(level).count(level);
		int total = 0;
		for (int n : counts.values()) total += n;
		// Per level, unlike the bossbars, which sum every level; name it so the two cannot be confused.
		source.sendSuccess(() -> Component.literal("Paint in " + level.dimension().identifier() + ":"), false);
		if (total == 0) {
			source.sendSuccess(() -> Component.literal("Nothing painted"), false);
			return 0;
		}
		for (PaintColor color : PaintColor.values()) {
			int faces = counts.get(color);
			int percent = Math.round(PaintTally.share(counts, color) * 100);
			source.sendSuccess(() -> Component.literal(color.displayName + ": " + faces + " faces, " + percent + " %")
					.withStyle(style -> style.withColor(color.teamColor.textColor())), false);
		}
		return total;
	}

	private static int reset(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		int removed = PaintTally.of(level).reset(level);
		if (removed == 0) {
			source.sendSuccess(() -> Component.literal("Nothing painted"), false);
		} else {
			source.sendSuccess(() -> Component.literal("Removed " + removed + " paint blocks").withStyle(ChatFormatting.YELLOW), true);
		}
		return removed;
	}

	/**
	 * Re-read the config files an arena builder edits between rounds. Only the unpaintable list for now:
	 * the weapon tuning is edited from inside the game and written after every change, so re-reading it
	 * would throw away what {@code /rivals tune} just set.
	 */
	public static int reload(CommandSourceStack source) {
		int listed = Unpaintable.reload();
		source.sendSuccess(() -> Component.literal("Unpaintable: the #" + Rivals.MOD_ID + ":unpaintable tag plus "
				+ listed + " block" + (listed == 1 ? "" : "s") + " from " + Unpaintable.configPath()), true);
		return listed;
	}

	/** Weapon ids, plus the {@code reset} that takes the whole lot back to the defaults. */
	private static final SuggestionProvider<CommandSourceStack> WEAPONS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(
					Stream.concat(Stream.of(Weapon.values()).map(Weapon::commandId), Stream.of("reset")), builder);

	/**
	 * The parameters the weapon already typed answers to, plus its own {@code reset}. A weapon nobody
	 * recognises suggests nothing rather than everything: the next word is only meaningful once the
	 * first one is, and the failure message is where the valid names belong.
	 */
	private static final SuggestionProvider<CommandSourceStack> PARAMS = (ctx, builder) -> {
		Optional<Weapon> weapon = Weapon.byId(StringArgumentType.getString(ctx, "weapon"));
		if (weapon.isEmpty()) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(
				Stream.concat(WeaponTuning.params(weapon.get()).stream().map(param -> param.id), Stream.of("reset")), builder);
	};

	/** Every weapon's tuning that is off its default, or a word to say that none of it is. */
	private static int tuneAll(CommandSourceStack source) {
		if (WeaponTuning.allDefault()) {
			source.sendSuccess(() -> Component.literal("Weapon tuning: all defaults. " + WeaponTuning.configPath()), false);
			return 0;
		}
		int changed = 0;
		for (Weapon weapon : Weapon.values()) {
			WeaponTuning tuning = WeaponTuning.get(weapon);
			List<Param> params = tuning.changed();
			if (params.isEmpty()) continue;
			changed += params.size();
			String line = weapon.commandId() + ": " + params.stream()
					.map(param -> param.id + " " + WeaponTuning.number(tuning.value(param)) + " [" + WeaponTuning.number(tuning.defaultValue(param)) + "]")
					.collect(Collectors.joining(", "));
			source.sendSuccess(() -> Component.literal(line), false);
		}
		return changed;
	}

	/**
	 * One weapon's whole sheet, defaults in brackets behind anything that has moved — or, for the word
	 * {@code reset} in the weapon's place, every weapon back to the numbers it shipped with.
	 */
	private static int tuneWeapon(CommandSourceStack source, String weaponId) {
		if ("reset".equalsIgnoreCase(weaponId)) {
			int changed = 0;
			for (Weapon weapon : Weapon.values()) changed += WeaponTuning.get(weapon).changed().size();
			WeaponTuning.resetAll();
			WeaponTuning.save();
			int total = changed;
			source.sendSuccess(() -> Component.literal("Every weapon back to its defaults: " + total + " values")
					.withStyle(ChatFormatting.YELLOW), true);
			return total;
		}
		Optional<Weapon> found = weaponOr(source, weaponId);
		if (found.isEmpty()) return 0;
		Weapon weapon = found.get();
		WeaponTuning tuning = WeaponTuning.get(weapon);
		List<Param> params = WeaponTuning.params(weapon);
		source.sendSuccess(() -> Component.literal(weapon.displayName + " (" + weapon.commandId() + ")")
				.withStyle(ChatFormatting.AQUA), false);
		for (Param param : params) {
			boolean untouched = tuning.isDefault(param);
			String line = "  " + param.id + ": " + WeaponTuning.number(tuning.value(param))
					+ (untouched ? "" : " [" + WeaponTuning.number(tuning.defaultValue(param)) + "]")
					+ "  (" + param.range() + ")";
			source.sendSuccess(() -> Component.literal(line).withStyle(untouched ? ChatFormatting.GRAY : ChatFormatting.WHITE), false);
		}
		return params.size();
	}

	/** One number — or, for the word {@code reset} in the parameter's place, this weapon's whole sheet. */
	private static int tuneParam(CommandSourceStack source, String weaponId, String paramId) {
		Optional<Weapon> found = weaponOr(source, weaponId);
		if (found.isEmpty()) return 0;
		Weapon weapon = found.get();
		WeaponTuning tuning = WeaponTuning.get(weapon);
		if ("reset".equalsIgnoreCase(paramId)) {
			int changed = tuning.changed().size();
			tuning.reset();
			WeaponTuning.save();
			source.sendSuccess(() -> Component.literal(weapon.displayName + " back to its defaults: " + changed + " values")
					.withStyle(ChatFormatting.YELLOW), true);
			return changed;
		}
		Optional<Param> wanted = paramOr(source, weapon, paramId);
		if (wanted.isEmpty()) return 0;
		Param param = wanted.get();
		String line = weapon.commandId() + " " + param.id + ": " + WeaponTuning.number(tuning.value(param))
				+ (tuning.isDefault(param) ? " (default)" : " [default " + WeaponTuning.number(tuning.defaultValue(param)) + "]")
				+ ", " + param.range();
		source.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}

	/**
	 * Move one number and write the file. Reported old → new because a tuning session is a series of
	 * small nudges, and what the number just was is the thing you want back when a nudge went wrong.
	 */
	private static int tuneSet(CommandSourceStack source, String weaponId, String paramId, double value) {
		Optional<Weapon> found = weaponOr(source, weaponId);
		if (found.isEmpty()) return 0;
		Weapon weapon = found.get();
		Optional<Param> wanted = paramOr(source, weapon, paramId);
		if (wanted.isEmpty()) return 0;
		Param param = wanted.get();
		// Several of these are loop bounds and spawn counts, so a number outside the range is refused
		// rather than clamped: silently getting a 4 for the 500 you typed is worse than being told no.
		if (!param.holds(value)) {
			source.sendFailure(Component.literal(param.id + " must be " + param.range() + ", not "
					+ WeaponTuning.number(value)).withStyle(ChatFormatting.RED));
			return 0;
		}
		WeaponTuning tuning = WeaponTuning.get(weapon);
		double was = tuning.set(param, value);
		WeaponTuning.save();
		source.sendSuccess(() -> Component.literal(weapon.commandId() + " " + param.id + ": "
				+ WeaponTuning.number(was) + " → " + WeaponTuning.number(value)
				+ (tuning.isDefault(param) ? " (the default)" : " [default " + WeaponTuning.number(tuning.defaultValue(param)) + "]")), true);
		return 1;
	}

	/** The named weapon, or a failure that says which names there are. */
	private static Optional<Weapon> weaponOr(CommandSourceStack source, String id) {
		Optional<Weapon> weapon = Weapon.byId(id);
		if (weapon.isEmpty()) {
			source.sendFailure(Component.literal("No weapon called \"" + id + "\". Try one of: " + Weapon.idList())
					.withStyle(ChatFormatting.RED));
		}
		return weapon;
	}

	/**
	 * The named parameter of that weapon, or a failure that lists the ones it has. Only the ones it
	 * has: offering the charger's {@code range_full} on the slosher would be offering a number that
	 * nothing reads.
	 */
	private static Optional<Param> paramOr(CommandSourceStack source, Weapon weapon, String id) {
		Optional<Param> param = Param.byId(id).filter(found -> WeaponTuning.applies(weapon, found));
		if (param.isEmpty()) {
			source.sendFailure(Component.literal("The " + weapon.displayName + " has no parameter called \"" + id
					+ "\". Try one of: " + WeaponTuning.paramList(weapon)).withStyle(ChatFormatting.RED));
		}
		return param;
	}

}
