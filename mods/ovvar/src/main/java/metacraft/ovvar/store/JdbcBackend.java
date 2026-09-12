package metacraft.ovvar.store;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import metacraft.ovvar.Ovvar;
import metacraft.ovvar.content.Chapter;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.SpotPlacements;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Designs in one table shared by every server:
 * <pre>
 * owner CHAR(36), chapter VARCHAR(32), version BIGINT, patches TEXT (a JSON list of cell.patch keys),
 * updated_at BIGINT (epoch millis), PRIMARY KEY (owner, chapter)
 * </pre>
 * Standard SQL only, so MariaDB, MySQL, PostgreSQL, H2 and SQLite all take it. The compare-and-set
 * is an {@code INSERT} for a row that must not exist (a duplicate key is the conflict) or an
 * {@code UPDATE ... WHERE version = ?} whose row count says whether it won. One connection, reopened
 * after any failure; a single store thread means no pool is needed.
 */
public final class JdbcBackend implements DesignBackend {
	private final DesignStoreConfig.Jdbc config;
	private final String table;
	private Connection connection;

	public JdbcBackend(DesignStoreConfig.Jdbc config) {
		this.config = config;
		if (!config.table().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("designs.jdbc.table is not a plain identifier: " + config.table());
		this.table = config.table();
	}

	private Connection connection() throws SQLException {
		if (connection != null) {
			try {
				if (connection.isValid(config.connectTimeoutSeconds())) return connection;
			} catch (SQLException ignored) {
				// fall through and reopen
			}
			closeQuietly();
		}
		if (!config.driverClass().isEmpty()) {
			try {
				Class.forName(config.driverClass());
			} catch (ClassNotFoundException e) {
				throw new SQLException("designs.jdbc.driver_class not found: " + config.driverClass(), e);
			}
		}
		DriverManager.setLoginTimeout(config.connectTimeoutSeconds());
		connection = DriverManager.getConnection(config.url(), config.user(), config.resolvedPassword());
		connection.setAutoCommit(true);
		try (Statement statement = connection.createStatement()) {
			statement.setQueryTimeout(config.queryTimeoutSeconds());
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
					+ "owner CHAR(36) NOT NULL, chapter VARCHAR(32) NOT NULL, version BIGINT NOT NULL, "
					+ "patches TEXT NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY (owner, chapter))");
		}
		Ovvar.LOGGER.info("[ovvar] design store connected: {}", describe());
		return connection;
	}

	private void closeQuietly() {
		if (connection == null) return;
		try {
			connection.close();
		} catch (SQLException ignored) {
			// closing a broken connection
		}
		connection = null;
	}

	private PreparedStatement prepare(String sql) throws SQLException {
		PreparedStatement statement = connection().prepareStatement(sql);
		statement.setQueryTimeout(config.queryTimeoutSeconds());
		return statement;
	}

	@Override
	public Map<Chapter, Design> loadAll(UUID owner) throws IOException {
		try (PreparedStatement statement = prepare("SELECT chapter, version, patches FROM " + table + " WHERE owner = ?")) {
			statement.setString(1, owner.toString());
			Map<Chapter, Design> out = new EnumMap<>(Chapter.class);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					String chapterId = rows.getString(1);
					Chapter chapter = null;
					for (Chapter c : Chapter.values()) if (c.id.equals(chapterId)) chapter = c;
					if (chapter == null) {
						Ovvar.LOGGER.warn("[ovvar] design store: {} has a design for unknown chapter '{}', ignored", owner, chapterId);
						continue;
					}
					out.put(chapter, new Design(decode(rows.getString(3)), rows.getLong(2)));
				}
			}
			return out;
		} catch (SQLException e) {
			closeQuietly();
			throw new IOException("design store: " + e.getMessage(), e);
		}
	}

	@Override
	public boolean store(DesignKey key, Design next, long expectedVersion) throws IOException {
		String patches = encode(next);
		long now = System.currentTimeMillis();
		try {
			if (expectedVersion == 0) {
				try (PreparedStatement statement = prepare("INSERT INTO " + table + " (owner, chapter, version, patches, updated_at) VALUES (?, ?, ?, ?, ?)")) {
					statement.setString(1, key.owner().toString());
					statement.setString(2, key.chapter().id);
					statement.setLong(3, next.version());
					statement.setString(4, patches);
					statement.setLong(5, now);
					statement.executeUpdate();
					return true;
				} catch (SQLIntegrityConstraintViolationException e) {
					return false;   // the row exists: someone else wrote first
				} catch (SQLException e) {
					// PostgreSQL reports a duplicate key with SQLSTATE 23505 but not that subclass.
					if ("23505".equals(e.getSQLState()) || (e.getSQLState() != null && e.getSQLState().startsWith("23"))) return false;
					throw e;
				}
			}
			try (PreparedStatement statement = prepare("UPDATE " + table + " SET version = ?, patches = ?, updated_at = ? WHERE owner = ? AND chapter = ? AND version = ?")) {
				statement.setLong(1, next.version());
				statement.setString(2, patches);
				statement.setLong(3, now);
				statement.setString(4, key.owner().toString());
				statement.setString(5, key.chapter().id);
				statement.setLong(6, expectedVersion);
				return statement.executeUpdate() == 1;
			}
		} catch (SQLException e) {
			closeQuietly();
			throw new IOException("design store: " + e.getMessage(), e);
		}
	}

	private static String encode(Design design) throws IOException {
		List<Placement> list = SpotPlacements.asPlacementList(design.placements());
		return Placement.CODEC.listOf().encodeStart(JsonOps.INSTANCE, list).getOrThrow(IOException::new).toString();
	}

	private static SpotPlacements decode(String json) throws IOException {
		try {
			List<Placement> list = Placement.CODEC.listOf().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow(IOException::new);
			return list.isEmpty() ? null : SpotPlacements.fromList(list).getOrThrow(IOException::new);
		} catch (RuntimeException e) {
			throw new IOException("design store: bad patches column " + json, e);
		}
	}

	@Override
	public String describe() {
		return "jdbc " + config.url().replaceAll("(?i)password=[^&;]*", "password=***") + " table " + table
				+ (connection == null ? " (not connected)" : "");
	}

	@Override
	public void close() {
		closeQuietly();
	}
}
