package nu.metacraft.rivals;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import nu.metacraft.rivals.paint.PaintTally;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * One bossbar per colour that has been painted this session, showing that colour's share of all
 * paint in the overworld, refreshed once a second for every online player. Cleared on server stop,
 * together with the tallies.
 */
public final class ScoreBars {
	private static final int REFRESH_TICKS = 20;
	private static final Map<PaintColor, ServerBossEvent> BARS = new EnumMap<>(PaintColor.class);

	private ScoreBars() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % REFRESH_TICKS == 0) refresh(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			BARS.values().forEach(ServerBossEvent::removeAllPlayers);
			BARS.clear();
			PaintTally.clearAll();
		});
	}

	static void refresh(MinecraftServer server) {
		ServerLevel level = server.overworld();
		Map<PaintColor, Integer> counts = PaintTally.of(level).count(level);
		for (PaintColor color : PaintColor.values()) {
			int faces = counts.get(color);
			if (faces == 0 && !BARS.containsKey(color)) continue;
			ServerBossEvent bar = BARS.computeIfAbsent(color, c -> new ServerBossEvent(UUID.randomUUID(),
					Component.literal(c.displayName), c.barColor, BossEvent.BossBarOverlay.PROGRESS));
			float share = PaintTally.share(counts, color);
			bar.setName(Component.literal(color.displayName + " " + Math.round(share * 100) + " %"));
			bar.setProgress(share);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				bar.addPlayer(player);
			}
		}
	}
}
