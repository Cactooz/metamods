package nu.metacraft.rivals;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import nu.metacraft.rivals.gun.PaintBall;
import nu.metacraft.rivals.gun.PaintGun;
import nu.metacraft.rivals.gun.Recoil;
import nu.metacraft.rivals.paint.PaintBlocks;
import nu.metacraft.rivals.pack.RivalsPack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metacraft Rivals: a Splatoon-style paint prototype for vanilla clients, via Polymer.
 *
 * Order matters: blocks and the entity first (the gun refers to both), then the pack (which must be
 * required because a client without it sees sculk veins instead of paint), then commands and score.
 */
public class Rivals implements ModInitializer {
	public static final String MOD_ID = "metacraft-rivals";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		PaintBlocks.register();
		PaintBall.register();
		PaintGun.register();
		RivalsPack.init();
		RivalsCommands.register();
		ScoreBars.init();
		Recoil.init();
		LOGGER.info("[{}] ready", MOD_ID);
	}
}
