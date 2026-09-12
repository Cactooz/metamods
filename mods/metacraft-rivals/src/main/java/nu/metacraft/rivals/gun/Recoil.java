package nu.metacraft.rivals.gun;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * A camera kick for vanilla clients: a relative pitch nudge up on the shot, eased back most of the way on
 * the next tick. Only real server players with a connection get packets; mock players are skipped.
 */
public final class Recoil {
	public static final float KICK_PITCH = -2.5f;
	public static final float SETTLE_PITCH = 1.8f;
	private static final List<ServerPlayer> SETTLE_NEXT_TICK = new ArrayList<>();

	private Recoil() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (SETTLE_NEXT_TICK.isEmpty()) return;
			for (ServerPlayer player : List.copyOf(SETTLE_NEXT_TICK)) {
				if (player.connection != null && !player.isRemoved()) {
					player.connection.send(new ClientboundPlayerRotationPacket(0f, true, SETTLE_PITCH, true));
				}
			}
			SETTLE_NEXT_TICK.clear();
		});
	}

	public static void kick(Player shooter) {
		if (!(shooter instanceof ServerPlayer player) || player.connection == null) return;
		player.connection.send(new ClientboundPlayerRotationPacket(0f, true, KICK_PITCH, true));
		SETTLE_NEXT_TICK.add(player);
	}

	public static int pending() {
		return SETTLE_NEXT_TICK.size();
	}
}
