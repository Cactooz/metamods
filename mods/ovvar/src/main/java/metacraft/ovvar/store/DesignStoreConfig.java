package metacraft.ovvar.store;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

/**
 * The {@code designs} block of {@code config/ovvar.json}: where the players' wardrobes (their
 * sewn patches per chapter and their stash of unsewn ones) live, and what sewing does when that
 * store cannot be reached. Every key has a default, so an older file without the block still loads.
 *
 * @param backend				{@code file}: one JSON per design under {@link #fileDirectory}; {@code jdbc}: a table
 * @param fileDirectory		  for the file backend: an absolute directory, or "" for {@code <world>/ovvar/wardrobes}
 * @param jdbc				   the JDBC settings; only read when the backend is {@code jdbc}
 * @param bindOnPickup		   an ovve with no owner becomes owned by the first player whose inventory ticks it
 * @param othersOvve			 what happens with an ovve owned by somebody else: {@code block} (the default:
 *							   it cannot be worn — the armour slot refuses it and a forced one is taken off
 *							   again — and nothing may be sewn on or off it), {@code rebind} (it becomes the
 *							   holder's, showing their design) or {@code allow} (anyone may wear it and it
 *							   keeps showing its owner's design, how it was before)
 * @param editRequiresOwner	  only an owned ovve's owner may sew on it or unpick from it (the default);
 *							   false lets anyone with shears change somebody else's design
 * @param sewWhenUnreachable	 with the store down, a sew still goes on the ovve and the write is queued
 *							   (retried every {@link #retrySeconds}); false refuses and keeps the patch in hand
 * @param unpickWhenUnreachable  with the store down, an unpick still hands the patch back and the write
 *							   is queued. Riskier than sewing (it is the dupe direction); off by default
 * @param retrySeconds		   how often a failed load or a queued write is retried
 * @param logQueries			 log every load and store at INFO (debugging)
 */
public record DesignStoreConfig(
		Backend backend, String fileDirectory, Jdbc jdbc, boolean bindOnPickup, OthersOvve othersOvve,
		boolean editRequiresOwner, boolean sewWhenUnreachable, boolean unpickWhenUnreachable, int retrySeconds,
		boolean logQueries
) {
	/** What somebody else's ovve is to this player: unwearable (the default), theirs to take over, or free to wear. */
	public enum OthersOvve implements StringRepresentable {
		BLOCK("block"), REBIND("rebind"), ALLOW("allow");

		public static final Codec<OthersOvve> CODEC = StringRepresentable.fromEnum(OthersOvve::values);
		private final String name;

		OthersOvve(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public enum Backend implements StringRepresentable {
		FILE("file"), JDBC("jdbc");

		public static final Codec<Backend> CODEC = StringRepresentable.fromEnum(Backend::values);
		private final String name;

		Backend(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	/**
	 * @param url				   e.g. {@code jdbc:mariadb://db.example:3306/metacraft} or {@code jdbc:postgresql://db.example/metacraft}
	 * @param user				  database user
	 * @param password			  its password, in the file; leave "" to use {@link #passwordEnv} instead
	 * @param passwordEnv		   name of an environment variable holding the password (preferred over the file)
	 * @param table				 the table; created if missing
	 * @param driverClass		   forces a driver class ("" lets the URL pick one; MariaDB/MySQL and PostgreSQL are bundled)
	 * @param connectTimeoutSeconds login timeout
	 * @param queryTimeoutSeconds   per-statement timeout
	 */
	public record Jdbc(
			String url, String user, String password, String passwordEnv, String table, String driverClass,
			int connectTimeoutSeconds, int queryTimeoutSeconds
	) {
		public static final Jdbc DEFAULT = new Jdbc("jdbc:mariadb://localhost:3306/metacraft", "metacraft", "", "OVVAR_DB_PASSWORD",
				"ovve_wardrobes", "", 5, 5);
		public static final Codec<Jdbc> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.optionalFieldOf("url", DEFAULT.url).forGetter(Jdbc::url),
				Codec.STRING.optionalFieldOf("user", DEFAULT.user).forGetter(Jdbc::user),
				Codec.STRING.optionalFieldOf("password", DEFAULT.password).forGetter(Jdbc::password),
				Codec.STRING.optionalFieldOf("password_env", DEFAULT.passwordEnv).forGetter(Jdbc::passwordEnv),
				Codec.STRING.optionalFieldOf("table", DEFAULT.table).forGetter(Jdbc::table),
				Codec.STRING.optionalFieldOf("driver_class", DEFAULT.driverClass).forGetter(Jdbc::driverClass),
				Codec.intRange(1, 600).optionalFieldOf("connect_timeout_seconds", DEFAULT.connectTimeoutSeconds).forGetter(Jdbc::connectTimeoutSeconds),
				Codec.intRange(1, 600).optionalFieldOf("query_timeout_seconds", DEFAULT.queryTimeoutSeconds).forGetter(Jdbc::queryTimeoutSeconds)
		).apply(instance, Jdbc::new));

		/** The password to use: the environment variable when named and set, else the file's. */
		public String resolvedPassword() {
			if (!passwordEnv.isEmpty()) {
				String env = System.getenv(passwordEnv);
				if (env != null && !env.isEmpty()) return env;
			}
			return password;
		}
	}

	public static final DesignStoreConfig DEFAULT = new DesignStoreConfig(Backend.FILE, "", Jdbc.DEFAULT, true,
			OthersOvve.BLOCK, true, false, false, 15, false);

	/** The same settings with another {@code others_ovve} (a test, a command). */
	public DesignStoreConfig othersOvve(OthersOvve value) {
		return new DesignStoreConfig(backend, fileDirectory, jdbc, bindOnPickup, value, editRequiresOwner,
				sewWhenUnreachable, unpickWhenUnreachable, retrySeconds, logQueries);
	}

	/** The same settings with another {@code edit_requires_owner}. */
	public DesignStoreConfig editRequiresOwner(boolean value) {
		return new DesignStoreConfig(backend, fileDirectory, jdbc, bindOnPickup, othersOvve, value,
				sewWhenUnreachable, unpickWhenUnreachable, retrySeconds, logQueries);
	}

	public static final MapCodec<DesignStoreConfig> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Backend.CODEC.optionalFieldOf("backend", DEFAULT.backend).forGetter(DesignStoreConfig::backend),
			Codec.STRING.optionalFieldOf("file_directory", DEFAULT.fileDirectory).forGetter(DesignStoreConfig::fileDirectory),
			Jdbc.CODEC.optionalFieldOf("jdbc", DEFAULT.jdbc).forGetter(DesignStoreConfig::jdbc),
			Codec.BOOL.optionalFieldOf("bind_on_pickup", DEFAULT.bindOnPickup).forGetter(DesignStoreConfig::bindOnPickup),
			OthersOvve.CODEC.optionalFieldOf("others_ovve", DEFAULT.othersOvve).forGetter(DesignStoreConfig::othersOvve),
			Codec.BOOL.optionalFieldOf("edit_requires_owner", DEFAULT.editRequiresOwner).forGetter(DesignStoreConfig::editRequiresOwner),
			Codec.BOOL.optionalFieldOf("sew_when_unreachable", DEFAULT.sewWhenUnreachable).forGetter(DesignStoreConfig::sewWhenUnreachable),
			Codec.BOOL.optionalFieldOf("unpick_when_unreachable", DEFAULT.unpickWhenUnreachable).forGetter(DesignStoreConfig::unpickWhenUnreachable),
			Codec.intRange(1, 3600).optionalFieldOf("retry_seconds", DEFAULT.retrySeconds).forGetter(DesignStoreConfig::retrySeconds),
			Codec.BOOL.optionalFieldOf("log_queries", DEFAULT.logQueries).forGetter(DesignStoreConfig::logQueries)
	).apply(instance, DesignStoreConfig::new));
}
