package metacraft.ovvar.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import metacraft.ovvar.content.Chapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Designs as files: {@code <dir>/<owner uuid>/<chapter>.json}. The default store — the world's
 * own {@code ovvar/designs} — or any directory the servers share. The version check re-reads
 * the file before writing and the write is a temp file renamed into place, which is safe on a
 * local disk and good enough on a shared mount for players who can only be on one server at a
 * time; a real network wants {@link JdbcBackend}.
 */
public final class FileBackend implements DesignBackend {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path dir;

	public FileBackend(Path dir) {
		this.dir = dir;
	}

	private Path file(DesignKey key) {
		return dir.resolve(key.owner().toString()).resolve(key.chapter().id + ".json");
	}

	@Override
	public Map<Chapter, Design> loadAll(UUID owner) throws IOException {
		Map<Chapter, Design> out = new EnumMap<>(Chapter.class);
		for (Chapter chapter : Chapter.values()) {
			Design design = read(file(new DesignKey(owner, chapter)));
			if (design != null) out.put(chapter, design);
		}
		return out;
	}

	private static Design read(Path path) throws IOException {
		if (!Files.exists(path)) return null;
		try {
			JsonElement json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
			return Design.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(msg -> new IOException(path + ": " + msg));
		} catch (RuntimeException e) {
			throw new IOException(path + ": " + e.getMessage(), e);
		}
	}

	@Override
	public synchronized boolean store(DesignKey key, Design next, long expectedVersion) throws IOException {
		Path path = file(key);
		Design current = read(path);
		long held = current == null ? 0 : current.version();
		if (held != expectedVersion) return false;
		Files.createDirectories(path.getParent());
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(Design.CODEC.encodeStart(JsonOps.INSTANCE, next).getOrThrow(IOException::new)), StandardCharsets.UTF_8);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
		return true;
	}

	@Override
	public String describe() {
		return "file " + dir.toAbsolutePath();
	}
}
