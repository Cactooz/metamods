package nu.metacraft.rivals.gun;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ink on your screen: how much enemy paint is in the player's face, and the one number the post effect
 * that draws it needs. A hit from an enemy weapon throws {@link #PER_DAMAGE} per point of damage onto
 * the glass, standing in enemy ink adds {@link #STANDING_GAIN} a tick, and it runs off at
 * {@link #DECAY} a tick — so a full hit blinds for a couple of seconds and then clears itself.
 *
 * <p>Getting the number to the shader is the interesting half. A server-side mod cannot send a uniform
 * to a vanilla client's post effect, so it writes the number into the frame the shader reads: every
 * player with ink on screen is held on a title consisting of one glyph from the mod's own {@code data}
 * font — a 2x2 white square — whose style colour <em>is</em> the meter:
 *
 * <ul>
 * <li>red 255 and green below 16 is the signature, which nothing else on a Minecraft screen is for
 *     eight flat pixels in a row (the probe checks two samples a step apart);</li>
 * <li>green's low nibble is the enemy team's index, which picks the ink colour;</li>
 * <li>blue is the amount, 0..255.</li>
 * </ul>
 *
 * <p>The title is set up once with {@link #STAY} ticks of stay and no fade either way, so it neither
 * animates nor expires, and the text is only re-sent when the value actually changes and at most every
 * {@link #SEND_EVERY} ticks. The shader paints ink over the marker and its shadow, so the player never
 * sees the number they are being told. No ink, no team, dead or spectating: the title is cleared
 * outright rather than sent as a zero, so nothing of ours is on screen at all.
 */
public final class InkOnScreen {
	/** The most ink a screen can hold; the shader's amount byte is 0..{@code MAX}. */
	public static final int MAX = 255;
	/** Ink per point of damage taken from an enemy weapon. Four hearts of charger fills the screen. */
	public static final int PER_DAMAGE = 25;
	/** Ink a tick while standing in enemy paint. */
	public static final int STANDING_GAIN = 2;
	/** Ink that runs off a tick otherwise. */
	public static final int DECAY = 4;
	/** The fewest ticks between two title packets for the same player. */
	public static final int SEND_EVERY = 2;
	/** Title timings: no fade, and a stay long enough that it never runs out of its own accord. */
	public static final int STAY = 1_000_000;
	/** The font the data pixel is drawn in, and its one character, from the private-use area. */
	public static final String FONT = "data";
	public static final char MARKER = '\uE000';
	/** The signature the shader looks for: red at full. */
	public static final int SIGNATURE_RED = 0xFF;

	/** One player's meter. {@code sent} is the last amount a packet carried, -1 for none. */
	private static final class Meter {
		private int amount;
		private PaintColor color;
		private int sent = -1;
		private long sentAt = Long.MIN_VALUE;
		private boolean animated;

		private Meter(PaintColor color) {
			this.color = color;
		}
	}

	private static final Map<UUID, Meter> METERS = new HashMap<>();

	private InkOnScreen() {}

	public static void init() {
		// A player who logs out with ink on screen is never ticked again: drop the meter rather than
		// leave a stale amount for whoever rejoins on that UUID.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.getPlayer()));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> METERS.clear());
	}

	/** How much ink is on {@code player}'s screen, 0 when there is none. */
	public static int amount(Player player) {
		Meter meter = METERS.get(player.getUUID());
		return meter == null ? 0 : meter.amount;
	}

	/** Whose ink it is, or null when the screen is clean. */
	public static @Nullable PaintColor color(Player player) {
		Meter meter = METERS.get(player.getUUID());
		return meter == null ? null : meter.color;
	}

	/**
	 * A hit landed. {@code by} is the shooter's colour, which is the ink that ends up on the victim's
	 * screen; anything that is not a player takes no ink, and neither does a hit that did no damage.
	 */
	public static void hit(Entity victim, PaintColor by, float damage) {
		if (!(victim instanceof ServerPlayer player) || damage <= 0) return;
		add(player, by, Math.round(damage * PER_DAMAGE));
	}

	/** Ink for a tick spent standing in {@code enemy}'s paint. */
	public static void standing(Player player, PaintColor enemy) {
		if (player instanceof ServerPlayer server) add(server, enemy, STANDING_GAIN);
	}

	private static void add(ServerPlayer player, PaintColor color, int ink) {
		if (ink <= 0) return;
		Meter meter = METERS.computeIfAbsent(player.getUUID(), key -> new Meter(color));
		// The newest ink is the ink you see: a DATA hit on a screen full of IT turns it red.
		meter.color = color;
		meter.amount = Math.min(MAX, meter.amount + ink);
	}

	/**
	 * One tick of a player's meter: the decay, and the packet if the number the client is holding is out
	 * of date. Called from {@link nu.metacraft.rivals.PlayerTick} once the tick knows whether the player
	 * is standing in enemy ink, because that is where the ink is added.
	 */
	public static void tick(Player player, long now) {
		if (!(player instanceof ServerPlayer server)) return;
		Meter meter = METERS.get(player.getUUID());
		if (meter == null) return;
		// Not in a match, not alive, not playing: no meter and nothing on screen.
		if (player.isSpectator() || !player.isAlive() || PaintColor.byTeam(player.getTeam()).isEmpty()) {
			clear(server);
			return;
		}
		meter.amount = Math.max(0, meter.amount - DECAY);
		if (meter.amount == 0) {
			clear(server);
			return;
		}
		if (meter.amount == meter.sent || now - meter.sentAt < SEND_EVERY) return;
		if (server.connection == null) return;
		if (!meter.animated) {
			server.connection.send(new ClientboundSetTitlesAnimationPacket(0, STAY, 0));
			meter.animated = true;
		}
		server.connection.send(new ClientboundSetTitleTextPacket(title(meter.color, meter.amount)));
		meter.sent = meter.amount;
		meter.sentAt = now;
	}

	/**
	 * The data pixel as a component: the font's one glyph, coloured (255, team, amount). The style's own
	 * colour is what the font renderer paints the glyph with, and the title's fade alpha is 255 for the
	 * whole of {@link #STAY}, so the texels come out exactly this value for the shader to read back.
	 */
	public static Component title(PaintColor color, int amount) {
		int rgb = SIGNATURE_RED << 16 | (color.ordinal() & 0xF) << 8 | Math.max(0, Math.min(MAX, amount));
		return Component.literal(String.valueOf(MARKER))
				.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withFont(new FontDescription.Resource(Rivals.id(FONT))));
	}

	/** Wipe the meter and take the title off the player's screen. */
	public static void clear(ServerPlayer player) {
		Meter meter = METERS.remove(player.getUUID());
		if (meter == null || player.connection == null) return;
		player.connection.send(new ClientboundClearTitlesPacket(true));
	}

	/** Drop a player's meter without touching their connection: a disconnect, a server stop. */
	public static void forget(Player player) {
		METERS.remove(player.getUUID());
	}

	/** For tests: no meters anywhere. */
	public static void clearAll() {
		METERS.clear();
	}
}
