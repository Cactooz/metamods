package nu.metacraft.rivals;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;
import nu.metacraft.rivals.gun.InkOnScreen;
import nu.metacraft.rivals.gun.Roll;
import nu.metacraft.rivals.gun.WeaponMenu;
import nu.metacraft.rivals.gun.WeaponSelector;

import java.util.List;
import java.util.Optional;

/**
 * What being between matches means for a player: adventure mode, no gun, and a weapon selector in hand.
 *
 * <p>Adventure because the lobby is not a place to mine the arena from, and because a paint weapon in a
 * lobby is a paint weapon used on the arena before the round starts. Ops keep whatever mode they are in —
 * an operator in the lobby is usually building it — and the permission asked is the module's own
 * {@code metacraft.rivals}, the same one the admin commands use, so a server with a permissions plugin can
 * hand it out without handing out op.
 *
 * <p>Called from the match's own transitions, from {@code /rivals match} and from the join hook. A player
 * who joins while a match is <em>playing</em> is not given the lobby treatment at all: they are added to
 * the match ({@link Match#addMidMatch}), which arms them and gives them the respawn grace.
 *
 * <p>Respawning in the lobby puts a dressed player back on their own team's spawn and everyone else at the
 * world spawn — the same rule as in a match, minus the freeze, so that a lobby death does not scatter
 * people across the map.
 */
public final class Lobby {
	/** The permission that keeps a player's own game mode. */
	public static final String ADMIN_PERMISSION = "metacraft.rivals";

	private Lobby() {}

	public static void init() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> receiveOnJoin(handler.getPlayer()));
		// A lobby death: the team spawn if they are dressed, the world spawn if not. The match's own hook
		// answers a death during PLAYING and this one steps aside for it.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (alive || Match.state() == Match.State.PLAYING) return;
			sendToSpawn(newPlayer);
		});
	}

	/** A player arriving: into the match if one is on, into the lobby otherwise. */
	public static void receiveOnJoin(ServerPlayer player) {
		if (Match.state() == Match.State.PLAYING
				&& Match.addMidMatch(player, player.level().getServer().getTickCount())) {
			player.sendSystemMessage(Component.literal("A match is running — you are in it. Good luck.")
					.withStyle(ChatFormatting.GREEN));
			return;
		}
		receive(player);
	}

	/**
	 * The lobby treatment: no paint weapon, one selector, adventure mode unless they are an admin, a clean
	 * screen and no roll. Returns how many paint weapons were taken off them, which is what the tests read.
	 */
	public static int receive(ServerPlayer player) {
		int taken = WeaponMenu.sweep(player);
		give(player);
		if (!isAdmin(player)) player.setGameMode(GameType.ADVENTURE);
		InkOnScreen.clear(player);
		Roll.stop(player);
		Match.thaw(player);
		return taken;
	}

	/** Everybody at once: what the match calls on its way back to the lobby. */
	public static int receiveAll(List<ServerPlayer> players) {
		int taken = 0;
		for (ServerPlayer player : players) taken += receive(player);
		return taken;
	}

	/**
	 * One selector, and only one. A player who is handed a second every time the round ends finishes the
	 * evening with a hotbar full of compasses.
	 */
	public static boolean give(ServerPlayer player) {
		if (WeaponSelector.carried(player)) return false;
		ItemStack selector = WeaponSelector.stack();
		if (!player.getInventory().add(selector)) player.drop(selector, false, Prediction.SERVER_ONLY);
		return true;
	}

	/** Their own team's spawn if their ovve says which, the world spawn otherwise. */
	public static void sendToSpawn(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) return;
		Optional<PaintColor> color = OvveTeams.worn(player).or(() -> PaintColor.byTeam(player.getTeam()));
		Optional<Arena.Spawn> spawn = color.flatMap(c -> Arena.of(level).spawn(c));
		if (spawn.isPresent()) {
			Arena.Spawn at = spawn.get();
			player.teleportTo(level, at.pos().x, at.pos().y, at.pos().z, java.util.Set.<Relative>of(),
					at.yaw(), at.pitch(), true);
		} else {
			// The level's own respawn point: the world spawn, and its stored look with it.
			LevelData.RespawnData world = level.getRespawnData();
			BlockPos at = world.pos();
			player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
					java.util.Set.<Relative>of(), world.yaw(), world.pitch(), true);
		}
		InkOnScreen.clear(player);
	}

	/** Whoever keeps their own game mode in the lobby. */
	public static boolean isAdmin(Player player) {
		return Permissions.check(player, ADMIN_PERMISSION, PermissionLevel.GAMEMASTERS);
	}
}
