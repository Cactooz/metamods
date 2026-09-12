package metacraft.ovvar.store;

import metacraft.ovvar.Ovvar;
import metacraft.ovvar.OvvarConfig;
import metacraft.ovvar.content.Chapter;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The designs this server knows: a cache per owner in front of the {@link DesignBackend}, filled
 * on join (and on demand for an ovve whose owner is elsewhere), written through with a
 * compare-and-set on every change. All state here belongs to the server thread; the backend is
 * only ever touched from one store thread, and every result comes back through
 * {@code server.execute}. Nothing here decides what a failure means for the player — the callers
 * do, from the config ({@link DesignStoreConfig}).
 */
public final class Designs {
	private Designs() {}

	public enum Outcome {
		/** Written, and the cache holds the new design. */
		OK,
		/** The store held a newer version (another server wrote first); the cache is being refreshed. Retry after that. */
		CONFLICT,
		/** The store could not be reached; nothing was written. */
		UNREACHABLE,
		/** The owner's designs have not been loaded (yet); a load has been requested. */
		NOT_LOADED
	}

	private record Queued(UnaryOperator<Design> change, String what) {}

	private static MinecraftServer server;
	private static DesignBackend backend;
	private static ExecutorService executor;
	private static final Map<UUID, Map<Chapter, Design>> CACHE = new HashMap<>();
	private static final Set<UUID> LOADING = new HashSet<>();
	private static final Map<UUID, Long> FAILED_AT = new HashMap<>();
	/** Writes accepted while the store was unreachable, per key in order; flushed by the tick. */
	private static final Map<DesignKey, Deque<Queued>> QUEUED = new HashMap<>();
	private static final Set<DesignKey> FLUSHING = new HashSet<>();
	private static long lastFailureLog;

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> {
			server = s;
			open(s, OvvarConfig.get().designs());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> close());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> fetch(handler.player.getUUID()));
		ServerTickEvents.END_SERVER_TICK.register(Designs::tick);
	}

	// ---- lifecycle

	/** Opens the backend the config names. The store thread is created here. */
	public static void open(MinecraftServer s, DesignStoreConfig config) {
		close();
		server = s;
		executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "ovvar-designs");
			t.setDaemon(true);
			return t;
		});
		backend = switch (config.backend()) {
			case FILE -> new FileBackend(config.fileDirectory().isEmpty()
					? s.getWorldPath(LevelResource.ROOT).resolve("ovvar").resolve("designs")
					: Path.of(config.fileDirectory()));
			case JDBC -> new JdbcBackend(config.jdbc());
		};
		Ovvar.LOGGER.info("[ovvar] designs: {}", backend.describe());
	}

	/** Swaps the backend (tests, {@code /ovvar store reconnect}); the cache is dropped and online players re-fetched. */
	public static void use(MinecraftServer s, DesignBackend newBackend) {
		close();
		server = s;
		executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "ovvar-designs");
			t.setDaemon(true);
			return t;
		});
		backend = newBackend;
		for (ServerPlayer player : s.getPlayerList().getPlayers()) fetch(player.getUUID());
	}

	private static void close() {
		if (executor != null) executor.shutdown();
		executor = null;
		if (backend != null) backend.close();
		backend = null;
		CACHE.clear();
		LOADING.clear();
		FAILED_AT.clear();
		QUEUED.clear();
		FLUSHING.clear();
	}

	public static boolean available() {
		return backend != null && executor != null;
	}

	private static DesignStoreConfig config() {
		return OvvarConfig.get().designs();
	}

	// ---- reading

	public static boolean loaded(UUID owner) {
		return CACHE.containsKey(owner);
	}

	/** The cached design, or empty when the owner is not loaded or has none for the chapter. */
	public static Optional<Design> cached(DesignKey key) {
		Map<Chapter, Design> designs = CACHE.get(key.owner());
		return designs == null ? Optional.empty() : Optional.ofNullable(designs.get(key.chapter()));
	}

	/** The cached design, or {@link Design#NONE}; only meaningful once {@link #loaded}. */
	public static Design current(DesignKey key) {
		return cached(key).orElse(Design.NONE);
	}

	/** Every cached design of an owner (for the show command). */
	public static Map<Chapter, Design> all(UUID owner) {
		return CACHE.getOrDefault(owner, Map.of());
	}

	/** Loads the owner's designs into the cache if not there; no-op while a load is in flight or shortly after one failed. */
	public static void fetch(UUID owner) {
		if (!available() || LOADING.contains(owner)) return;
		Long failed = FAILED_AT.get(owner);
		if (failed != null && System.currentTimeMillis() - failed < config().retrySeconds() * 1000L) return;
		LOADING.add(owner);
		DesignBackend b = backend;
		boolean log = config().logQueries();
		executor.submit(() -> {
			try {
				Map<Chapter, Design> designs = b.loadAll(owner);
				if (log) Ovvar.LOGGER.info("[ovvar] designs: loaded {} ({} chapter(s))", owner, designs.size());
				server.execute(() -> {
					LOADING.remove(owner);
					FAILED_AT.remove(owner);
					// Local changes queued while unreachable stay on top of what the store has.
					Map<Chapter, Design> merged = new EnumMap<>(Chapter.class);
					merged.putAll(designs);
					for (var entry : QUEUED.entrySet()) {
						if (!entry.getKey().owner().equals(owner)) continue;
						Design d = merged.getOrDefault(entry.getKey().chapter(), Design.NONE);
						for (Queued q : entry.getValue()) d = q.change.apply(d).withVersion(d.version());
						merged.put(entry.getKey().chapter(), d);
					}
					CACHE.put(owner, merged);
				});
			} catch (IOException | RuntimeException e) {
				unreachable("loading " + owner, e);
				server.execute(() -> {
					LOADING.remove(owner);
					FAILED_AT.put(owner, System.currentTimeMillis());
				});
			}
		});
	}

	/** Forgets and reloads an owner (the reload command, and after a conflict). */
	public static void refresh(UUID owner) {
		CACHE.remove(owner);
		FAILED_AT.remove(owner);
		fetch(owner);
	}

	// ---- writing

	/**
	 * Applies {@code change} to the owner's current design and writes it, expecting the store to
	 * hold the version the cache has. {@code done} runs on the server thread with the outcome; on
	 * {@link Outcome#OK} the cache already holds the result. A change that changes nothing is OK
	 * without a write.
	 */
	public static void update(DesignKey key, UnaryOperator<Design> change, Consumer<Outcome> done) {
		if (!available()) {
			done.accept(Outcome.UNREACHABLE);
			return;
		}
		if (!loaded(key.owner())) {
			fetch(key.owner());
			done.accept(Outcome.NOT_LOADED);
			return;
		}
		if (pending(key)) {
			// Earlier writes are still waiting for the store; this one queues behind them (or not, the caller's call).
			done.accept(Outcome.UNREACHABLE);
			return;
		}
		Design current = current(key);
		Design next = change.apply(current);
		if (next.samePatches(current.placements())) {
			done.accept(Outcome.OK);
			return;
		}
		next = next.withVersion(current.version() + 1);
		Design written = next;
		DesignBackend b = backend;
		boolean log = config().logQueries();
		executor.submit(() -> {
			try {
				boolean ok = b.store(key, written, current.version());
				if (log) Ovvar.LOGGER.info("[ovvar] designs: store {} v{} -> {}", key, written.version(), ok ? "ok" : "conflict");
				server.execute(() -> {
					if (ok) {
						put(key, written);
						done.accept(Outcome.OK);
					} else {
						refresh(key.owner());
						done.accept(Outcome.CONFLICT);
					}
				});
			} catch (IOException | RuntimeException e) {
				unreachable("storing " + key, e);
				server.execute(() -> done.accept(Outcome.UNREACHABLE));
			}
		});
	}

	/**
	 * Accepts a change without the store (the "when unreachable" config options): it is applied
	 * to the cache now, so the ovve shows it, and written when the store is back. If by then it
	 * no longer applies (the spot was taken on another server), it is dropped with a log line.
	 */
	public static void queue(DesignKey key, UnaryOperator<Design> change, String what) {
		Design current = current(key);
		put(key, change.apply(current).withVersion(current.version()));
		QUEUED.computeIfAbsent(key, k -> new ArrayDeque<>()).add(new Queued(change, what));
		Ovvar.LOGGER.warn("[ovvar] designs: {} on {} queued until the store is back", what, key);
	}

	public static boolean pending(DesignKey key) {
		Deque<Queued> queue = QUEUED.get(key);
		return queue != null && !queue.isEmpty();
	}

	public static int pendingCount() {
		return QUEUED.values().stream().mapToInt(Deque::size).sum();
	}

	private static void put(DesignKey key, Design design) {
		CACHE.computeIfAbsent(key.owner(), o -> new EnumMap<>(Chapter.class)).put(key.chapter(), design);
	}

	// ---- retrying

	private static void tick(MinecraftServer s) {
		if (!available() || s.getTickCount() % (config().retrySeconds() * 20L) != 0) return;
		for (DesignKey key : new ArrayList<>(QUEUED.keySet())) flush(key);
	}

	/** Replays a key's queued changes onto whatever the store now holds, one compare-and-set each. */
	private static void flush(DesignKey key) {
		if (FLUSHING.contains(key)) return;
		Deque<Queued> queue = QUEUED.get(key);
		if (queue == null || queue.isEmpty()) return;
		List<Queued> changes = new ArrayList<>(queue);
		FLUSHING.add(key);
		DesignBackend b = backend;
		executor.submit(() -> {
			int written = 0;
			try {
				Design fresh = b.loadAll(key.owner()).getOrDefault(key.chapter(), Design.NONE);
				for (Queued q : changes) {
					Design next = q.change.apply(fresh);
					if (next.samePatches(fresh.placements())) {
						Ovvar.LOGGER.warn("[ovvar] designs: queued {} on {} no longer applies, dropped", q.what, key);
						written++;
						continue;
					}
					next = next.withVersion(fresh.version() + 1);
					if (!b.store(key, next, fresh.version())) {
						// Someone else wrote in between: start over from the store next time round.
						break;
					}
					fresh = next;
					written++;
				}
				Design result = fresh;
				int n = written;
				server.execute(() -> {
					Deque<Queued> q = QUEUED.get(key);
					for (int i = 0; i < n && q != null && !q.isEmpty(); i++) q.removeFirst();
					if (q != null && q.isEmpty()) QUEUED.remove(key);
					put(key, result);
					if (pending(key)) {
						// Re-apply what is still queued on top, then let the next tick try again.
						Design d = result;
						for (Queued rest : QUEUED.get(key)) d = rest.change.apply(d).withVersion(d.version());
						put(key, d);
					} else {
						Ovvar.LOGGER.info("[ovvar] designs: {} caught up with the store", key);
					}
					FLUSHING.remove(key);
				});
			} catch (IOException | RuntimeException e) {
				unreachable("flushing " + key, e);
				server.execute(() -> FLUSHING.remove(key));
			}
		});
	}

	private static void unreachable(String doing, Exception e) {
		long now = System.currentTimeMillis();
		if (now - lastFailureLog > 10_000) {
			lastFailureLog = now;
			Ovvar.LOGGER.warn("[ovvar] designs: store unreachable while {}: {}", doing, e.toString());
		}
	}

	// ---- status

	public static String status() {
		if (!available()) return "designs: no store open";
		return "designs: " + backend.describe() + "; " + CACHE.size() + " owner(s) cached, " + LOADING.size() + " loading, "
				+ FAILED_AT.size() + " failed, " + pendingCount() + " queued write(s)";
	}
}
