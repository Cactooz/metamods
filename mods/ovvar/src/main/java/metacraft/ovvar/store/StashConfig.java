package metacraft.ovvar.store;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.GameType;

import java.util.List;

/**
 * The {@code stash} block of {@code config/ovvar.json}: what this server is, and when a player may
 * take a patch out of their stash (onto their ovve). Every key has a default.
 *
 * @param minigameServer	  a minigame server: the stash and every ovve are view-only here — no sewing,
 *							no unpicking, no stand sessions. Patches earned here still go to the stash
 * @param sewGameModes		game modes in which a player may sew from the stash (adventure players cannot)
 * @param ingameObjective	 a scoreboard objective; a player whose score in it is not 0 is in a game and
 *							cannot sew ("" to skip the check)
 * @param bankOnPickup		when a patch item in a player's inventory is banked to their stash and taken
 *							away: {@code minigame} (only on a minigame server, where it would be lost),
 *							{@code always}, or {@code never}
 * @param bankInCreative	  whether that applies to creative-mode players too (off: gamemasters keep
 *							the item, for showcase stands)
 * @param unpickToStash	   an unpicked patch goes to the stash (true) rather than into the hand as an item
 * @param withdraw			whether the stash lets a player take a patch out as an item here (a survival
 *							server; never on a minigame server)
 * @param anyStand			whether patches may be sewn and unpicked on any armour stand wearing an ovve,
 *							as before the stash; off: only on the private stand a stash session spawns
 * @param sessionReach		how far (blocks) a player may walk from their session stand before it ends
 * @param sessionSeconds	  how long a session lasts without a sew or unpick before it ends
 * @param explainInChat	   send the "what the stash is" lines when a patch is earned
 */
public record StashConfig(
		boolean minigameServer, List<GameType> sewGameModes, String ingameObjective, Bank bankOnPickup, boolean bankInCreative,
		boolean unpickToStash, boolean withdraw, boolean anyStand, double sessionReach, int sessionSeconds, boolean explainInChat
) {
	public enum Bank implements net.minecraft.util.StringRepresentable {
		MINIGAME("minigame"), ALWAYS("always"), NEVER("never");

		public static final Codec<Bank> CODEC = net.minecraft.util.StringRepresentable.fromEnum(Bank::values);
		private final String name;

		Bank(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	/** Are patch items banked on this server? */
	public boolean banksOnPickup() {
		return switch (bankOnPickup) {
			case MINIGAME -> minigameServer;
			case ALWAYS -> true;
			case NEVER -> false;
		};
	}

	/** May a player take a patch out of the stash as an item here? */
	public boolean canWithdraw() {
		return withdraw && !minigameServer;
	}

	private static final Codec<GameType> GAME_TYPE = Codec.STRING.comapFlatMap(
			s -> {
				GameType type = GameType.byName(s, null);
				return type == null ? com.mojang.serialization.DataResult.error(() -> "unknown game mode " + s) : com.mojang.serialization.DataResult.success(type);
			},
			GameType::getName);

	public static final StashConfig DEFAULT = new StashConfig(false, List.of(GameType.SURVIVAL, GameType.CREATIVE), "ingame",
			Bank.MINIGAME, false, false, true, false, 8.0, 300, true);

	public static final MapCodec<StashConfig> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("minigame_server", DEFAULT.minigameServer).forGetter(StashConfig::minigameServer),
			GAME_TYPE.listOf().optionalFieldOf("sew_game_modes", DEFAULT.sewGameModes).forGetter(StashConfig::sewGameModes),
			Codec.STRING.optionalFieldOf("ingame_objective", DEFAULT.ingameObjective).forGetter(StashConfig::ingameObjective),
			Bank.CODEC.optionalFieldOf("bank_on_pickup", DEFAULT.bankOnPickup).forGetter(StashConfig::bankOnPickup),
			Codec.BOOL.optionalFieldOf("bank_in_creative", DEFAULT.bankInCreative).forGetter(StashConfig::bankInCreative),
			Codec.BOOL.optionalFieldOf("unpick_to_stash", DEFAULT.unpickToStash).forGetter(StashConfig::unpickToStash),
			Codec.BOOL.optionalFieldOf("withdraw", DEFAULT.withdraw).forGetter(StashConfig::withdraw),
			Codec.BOOL.optionalFieldOf("any_stand", DEFAULT.anyStand).forGetter(StashConfig::anyStand),
			Codec.doubleRange(1, 64).optionalFieldOf("session_reach", DEFAULT.sessionReach).forGetter(StashConfig::sessionReach),
			Codec.intRange(10, 3600).optionalFieldOf("session_seconds", DEFAULT.sessionSeconds).forGetter(StashConfig::sessionSeconds),
			Codec.BOOL.optionalFieldOf("explain_in_chat", DEFAULT.explainInChat).forGetter(StashConfig::explainInChat)
	).apply(instance, StashConfig::new));
}
