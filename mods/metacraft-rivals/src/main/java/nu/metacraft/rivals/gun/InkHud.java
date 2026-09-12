package nu.metacraft.rivals.gun;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import nu.metacraft.rivals.PaintColor;
import org.jspecify.annotations.Nullable;

/** The ammo bar: action-bar text for every player holding a gun, refreshed every ten ticks and after each shot. */
public final class InkHud {
	private static final int REFRESH_TICKS = 10;
	private static final int CELLS = 10;

	private InkHud() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % REFRESH_TICKS != 0) return;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) show(player);
		});
	}

	/**
	 * Send the bar to a player holding a gun in either hand; silent otherwise. A player on no team gets
	 * the bar too, in grey: the tank is real (it drains and refills whatever the team), and a gun that
	 * shows nothing at all reads as broken rather than as "join a team first".
	 */
	public static void show(ServerPlayer player) {
		if (player.connection == null) return;
		ItemStack gun = heldGun(player);
		if (gun == null) return;
		PaintColor color = PaintColor.byTeam(player.getTeam()).orElse(null);
		long now = player.level().getServer().getTickCount();
		player.sendSystemMessage(bar(color, Ink.get(gun), Ink.isRefilling(gun, now), PaintGun.isSquid(player)), true);
	}

	static ItemStack heldGun(ServerPlayer player) {
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (stack.getItem() instanceof PaintGun) return stack;
		}
		return null;
	}

	/** The bar's text and colour; {@code color} null means no team, and the bar is grey. */
	public static Component bar(@Nullable PaintColor color, int ink, boolean refilling, boolean squid) {
		StringBuilder text = new StringBuilder("INK ");
		if (refilling) {
			text.append("REFILLING…");
		} else {
			int filled = (int) Math.round(ink * (double) CELLS / Ink.MAX);
			text.append("█".repeat(filled)).append("░".repeat(CELLS - filled)).append(' ').append(ink).append('/').append(Ink.MAX);
		}
		if (squid) text.append("  SQUID");
		MutableComponent component = Component.literal(text.toString());
		if (color == null) return component.withStyle(ChatFormatting.GRAY);
		return component.withStyle(style -> style.withColor(TextColor.fromRgb(color.rgb)));
	}
}
