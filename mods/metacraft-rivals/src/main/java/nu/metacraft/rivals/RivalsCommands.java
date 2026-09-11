package nu.metacraft.rivals;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import nu.metacraft.rivals.gun.PaintGun;
import nu.metacraft.rivals.paint.PaintTally;

import java.util.Map;
import java.util.Optional;

import static net.minecraft.commands.Commands.literal;

/** {@code /rivals setup | gun | score | reset}, for game masters (permission {@code metacraft.rivals}). */
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
						.then(literal("gun").executes(ctx -> gun(ctx.getSource())))
						.then(literal("score").executes(ctx -> score(ctx.getSource())))
						.then(literal("reset").executes(ctx -> reset(ctx.getSource())))));
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

	private static int gun(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ItemStack gun = new ItemStack(PaintGun.ITEM);
		if (!player.getInventory().add(gun)) player.drop(gun, false);
		source.sendSuccess(() -> Component.literal("Here is a paint gun. Right-click to shoot; join a team for colour."), false);
		return 1;
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
}
