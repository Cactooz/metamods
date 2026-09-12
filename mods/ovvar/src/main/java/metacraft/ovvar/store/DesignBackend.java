package metacraft.ovvar.store;

import metacraft.ovvar.content.Chapter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Where designs are kept. Called from the store's own thread only ({@link Designs}), never the
 * server thread. Every method may throw when the store cannot be reached; the caller treats that
 * as "unreachable, try later" — it never guesses.
 */
public interface DesignBackend {
	/** Every chapter's design this player has; empty map for a player with none. Versions are the store's. */
	Map<Chapter, Design> loadAll(UUID owner) throws IOException;

	/**
	 * Compare-and-set: writes {@code next} (its version must be {@code expectedVersion + 1}) if the
	 * store's current version for the key is {@code expectedVersion}, where 0 means "no row yet".
	 *
	 * @return false if the store held a different version, in which case nothing was written
	 */
	boolean store(DesignKey key, Design next, long expectedVersion) throws IOException;

	/** For the status command. */
	String describe();

	default void close() {}
}
