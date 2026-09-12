package nu.metacraft.rivals;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The paint colours. Each one owns a vanilla multiface block that clients are shown instead of the
 * paint block and whose texture the resource pack replaces with a splat. Sculk vein and resin clump
 * emit no light; glow lichen is vanilla's lit one (light 7), which is why it is the third, "special"
 * colour — what a client actually shows for our unlit paint block is unverified. The id doubles as the
 * vanilla team name.
 */
public enum PaintColor {
	MAGENTA("magenta", "Magenta", 0xEA2C8E, Blocks.SCULK_VEIN, TeamColor.LIGHT_PURPLE, BossEvent.BossBarColor.PINK),
	LIME("lime", "Lime", 0x8DE800, Blocks.RESIN_CLUMP, TeamColor.GREEN, BossEvent.BossBarColor.GREEN),
	CYAN("cyan", "Cyan", 0x00D5F5, Blocks.GLOW_LICHEN, TeamColor.AQUA, BossEvent.BossBarColor.BLUE);

	public final String id;
	public final String displayName;
	public final int rgb;
	public final Block donor;
	public final TeamColor teamColor;
	public final BossEvent.BossBarColor barColor;

	PaintColor(String id, String displayName, int rgb, Block donor, TeamColor teamColor, BossEvent.BossBarColor barColor) {
		this.id = id;
		this.displayName = displayName;
		this.rgb = rgb;
		this.donor = donor;
		this.teamColor = teamColor;
		this.barColor = barColor;
	}

	/** The donor block's registry path, e.g. {@code sculk_vein}: the blockstate file the pack overrides. */
	public String donorPath() {
		return BuiltInRegistries.BLOCK.getKey(donor).getPath();
	}

	/** Every colour's id, comma-separated, for messages that list the teams. */
	public static String idList() {
		List<String> ids = new ArrayList<>();
		for (PaintColor color : values()) ids.add(color.id);
		return String.join(", ", ids);
	}

	public static Optional<PaintColor> byId(String id) {
		for (PaintColor color : values()) {
			if (color.id.equals(id)) return Optional.of(color);
		}
		return Optional.empty();
	}

	/** The colour of a vanilla scoreboard team, matched by team name; empty for no team or an unknown name. */
	public static Optional<PaintColor> byTeam(@Nullable PlayerTeam team) {
		return team == null ? Optional.empty() : byId(team.getName());
	}
}
