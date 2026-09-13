package nu.metacraft.rivals;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import nu.metacraft.rivals.gun.Weapon;
import nu.metacraft.rivals.gun.WeaponChoice;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Is everybody ready to play? One line per online non-spectator player: their name, the team their ovve
 * puts them on, and the weapon they picked.
 *
 * <p>A player with no ovve has no team, and a player with no team cannot be given a colour, cannot paint
 * and cannot score — so a match that starts with one undressed player is a match with a passenger in it.
 * {@code /rivals ready} therefore <em>fails</em> when anyone is undressed, naming them, and
 * {@code /rivals match start} refuses on the same check unless the word {@code force} is added.
 *
 * <p>Spectators are left out rather than counted as undressed: a spectator is deliberately not playing.
 *
 * <p>The ovve lookup is a parameter on {@link #of(Collection, Function)} and only defaults to
 * {@link OvveTeams#worn}. That is for the tests: ovvar is a <em>soft</em> integration matched on an item's
 * registry id, so it is not on this module's classpath and no game test can put a real ovve on a mock
 * player. The tests pass a stand-in that runs the real {@link OvveTeams#colourOf(net.minecraft.resources.Identifier)}
 * over an {@code ovvar:}-namespaced id, so everything but the armour-slot read is the production path.
 */
public final class Readiness {
	private Readiness() {}

	/**
	 * One player's state. {@code team} is empty for a player wearing no ovve; {@code weapon} is empty for
	 * one who has never opened the picker (they would be handed {@link WeaponChoice#DEFAULT}).
	 */
	public record Line(ServerPlayer player, Optional<PaintColor> team, Optional<Weapon> weapon) {
		public boolean dressed() {
			return team.isPresent();
		}

		/** The line as it is printed: name, team or "no ovve", weapon or "none yet". */
		public String text() {
			return player.getScoreboardName() + " — " + team.map(color -> color.displayName).orElse("no ovve")
					+ ", " + weapon.map(w -> w.displayName).orElse("none yet");
		}
	}

	/** Everybody's state, and who among them is not dressed. */
	public record Report(List<Line> lines, List<ServerPlayer> undressed) {
		/** Ready when somebody is playing and everybody playing is dressed. */
		public boolean ready() {
			return !lines.isEmpty() && undressed.isEmpty();
		}

		/** The undressed players' names, comma-separated, for the refusal that names them. */
		public String undressedNames() {
			List<String> names = new ArrayList<>();
			for (ServerPlayer player : undressed) names.add(player.getScoreboardName());
			return String.join(", ", names);
		}
	}

	public static Report of(MinecraftServer server) {
		return of(server.getPlayerList().getPlayers(), OvveTeams::worn);
	}

	/** The same over a given set of players and a given ovve lookup. See the class note on why. */
	public static Report of(Collection<ServerPlayer> players, Function<Player, Optional<PaintColor>> ovve) {
		List<Line> lines = new ArrayList<>();
		List<ServerPlayer> undressed = new ArrayList<>();
		for (ServerPlayer player : players) {
			if (player.isSpectator()) continue;
			Optional<PaintColor> team = ovve.apply(player);
			Optional<Weapon> weapon = choice(player);
			Line line = new Line(player, team, weapon);
			lines.add(line);
			if (!line.dressed()) undressed.add(player);
		}
		return new Report(lines, undressed);
	}

	/** What this player picked, if the server is up far enough to have a saved choice at all. */
	private static Optional<Weapon> choice(ServerPlayer player) {
		@Nullable MinecraftServer server = player.level().getServer();
		return server == null ? Optional.empty() : WeaponChoice.of(server).get(player);
	}

	/**
	 * Print the report. Returns how many players are ready, or fails (and returns 0) when anybody is
	 * undressed — the failure is the point: it is what makes {@code /rivals ready} answerable by a script
	 * and what {@code match start} leans on.
	 */
	public static int report(CommandSourceStack source) {
		Report report = of(source.getServer());
		if (report.lines().isEmpty()) {
			source.sendFailure(Component.literal("Nobody is playing: no online player outside spectator mode")
					.withStyle(ChatFormatting.RED));
			return 0;
		}
		for (Line line : report.lines()) {
			source.sendSuccess(() -> Component.literal(line.text()).withStyle(style -> line.team()
					.map(color -> style.withColor(color.teamColor.textColor()))
					.orElse(style.applyFormat(ChatFormatting.GRAY))), false);
		}
		if (!report.ready()) {
			source.sendFailure(Component.literal("Not ready: " + report.undressedNames()
					+ " " + (report.undressed().size() == 1 ? "is" : "are") + " wearing no ovve. "
					+ "Get dressed, or start the match with /rivals match start <minutes> force")
					.withStyle(ChatFormatting.RED));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Ready: " + report.lines().size() + " player"
				+ (report.lines().size() == 1 ? "" : "s") + ", all dressed").withStyle(ChatFormatting.GREEN), false);
		return report.lines().size();
	}
}
