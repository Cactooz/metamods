package metacraft.ovvar;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * The MOTD every server announces itself with: which server this is and whether ovvar sewing works
 * here. Set once the server is up ({@link net.minecraft.server.MinecraftServer#setMotd}, which the
 * status answered to a ping is rebuilt from), so a player reads in the server list what they get
 * before joining — a survival server sews, a minigame server only shows the stash.
 */
public final class Motd {
	private Motd() {}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			String motd = text(OvvarConfig.get());
			server.setMotd(motd);
			Ovvar.LOGGER.info("[{}] motd: {}", Ovvar.MOD_ID, motd);
		});
	}

	/** The MOTD this config describes. Plain text: the vanilla MOTD here carries no formatting codes. */
	public static String text(OvvarConfig config) {
		return text(config.server().name(), config.stash().minigameServer());
	}

	/** The MOTD of a server of this name in this mode. */
	public static String text(String name, boolean minigameServer) {
		String server = name.isBlank() ? ServerConfig.DEFAULT.name() : name;
		return minigameServer
				? server + " Minigame · ovve stash only, no sewing"
				: server + " Survival · ovve sewing on stands, patches are items";
	}
}
