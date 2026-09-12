package metacraft.ovvar;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import metacraft.ovvar.store.DesignStoreConfig;
import metacraft.ovvar.store.StashConfig;
import net.fabricmc.loader.api.FabricLoader;
import nu.metacraft.lib.config.container.ConfigContainer;

import java.util.function.UnaryOperator;

/**
 * {@code config/ovvar.json}. Written with defaults when missing; a file that does not parse is an
 * error at startup rather than silently replaced. {@code /ovvar minigame} edits and saves it.
 *
 * @param sewingMinigame sew on a stand through the stitching dialog ({@link metacraft.ovvar.sewing.SewingGame})
 *					   instead of in one click
 * @param stitches	   how many stitches a cell-sized patch takes in the minigame; a longer outline
 *					   takes proportionally more ({@link metacraft.ovvar.sewing.Seam#stitchesFor})
 * @param server	     what this server calls itself, for the MOTD ({@link ServerConfig}, {@link Motd})
 * @param designs	    where the players' wardrobes live and what sewing does without it ({@link DesignStoreConfig})
 * @param stash		  this server's role, and the rules for taking patches out of the stash ({@link StashConfig})
 */
public record OvvarConfig(boolean sewingMinigame, int stitches, ServerConfig server, DesignStoreConfig designs, StashConfig stash) {
	public static final int MIN_STITCHES = 1, MAX_STITCHES = 16;
	public static final MapCodec<OvvarConfig> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.fieldOf("sewing_minigame").forGetter(OvvarConfig::sewingMinigame),
			Codec.intRange(MIN_STITCHES, MAX_STITCHES).fieldOf("stitches").forGetter(OvvarConfig::stitches),
			ServerConfig.CODEC.codec().optionalFieldOf("server", ServerConfig.DEFAULT).forGetter(OvvarConfig::server),
			DesignStoreConfig.CODEC.codec().optionalFieldOf("designs", DesignStoreConfig.DEFAULT).forGetter(OvvarConfig::designs),
			StashConfig.CODEC.codec().optionalFieldOf("stash", StashConfig.DEFAULT).forGetter(OvvarConfig::stash)
	).apply(instance, OvvarConfig::new));

	private static final ConfigContainer<OvvarConfig> CONTAINER = ConfigContainer.Builder.create(
			CODEC, () -> new OvvarConfig(true, 6, ServerConfig.DEFAULT, DesignStoreConfig.DEFAULT, StashConfig.DEFAULT)
	).build(FabricLoader.getInstance().getConfigDir().resolve(Ovvar.MOD_ID + ".json"));


	public static OvvarConfig get() {
		return CONTAINER.get();
	}

	public OvvarConfig minigame(boolean on, int stitches) {
		return new OvvarConfig(on, stitches > 0 ? stitches : stitches(), server, designs, stash);
	}

	/** The same config with other store settings (a test, a command). */
	public OvvarConfig designs(DesignStoreConfig designs) {
		return new OvvarConfig(sewingMinigame, stitches, server, designs, stash);
	}

	/** Re-reads {@code config/ovvar.json}. */
	public static void reload() {
		CONTAINER.reload();
	}

	public static void modify(UnaryOperator<OvvarConfig> config) {
		CONTAINER.replace(config.apply(CONTAINER.get()));
	}
}
