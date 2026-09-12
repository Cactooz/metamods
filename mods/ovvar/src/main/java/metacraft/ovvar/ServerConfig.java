package metacraft.ovvar;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The {@code server} block of {@code config/ovvar.json}: what this server calls itself. It is the
 * first half of the MOTD ({@link Motd}); the second half is what sewing does here, which is
 * {@code stash.minigame_server}. Every key has a default.
 *
 * @param name this server's name, as players read it in the server list (default "METAcraft")
 */
public record ServerConfig(String name) {
	public static final ServerConfig DEFAULT = new ServerConfig("METAcraft");

	public static final MapCodec<ServerConfig> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.STRING.optionalFieldOf("name", DEFAULT.name).forGetter(ServerConfig::name)
	).apply(instance, ServerConfig::new));
}
