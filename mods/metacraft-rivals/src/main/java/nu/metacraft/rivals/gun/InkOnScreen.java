package nu.metacraft.rivals.gun;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import nu.metacraft.rivals.PaintColor;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ink on your screen: how much enemy paint is in the player's face, and the one number the post effect
 * that draws it needs. A hit from an enemy weapon throws {@link #PER_DAMAGE} per point of damage onto
 * the glass and standing in enemy ink adds {@link #STANDING_GAIN} a tick, while {@link #DECAY} runs off
 * on every tick nothing added any — the decay is skipped on the ticks something did, so wading through
 * enemy paint fills the screen slowly rather than fighting the drain, and a hit blinds for a couple of
 * seconds and then clears itself.
 *
 * <p>Getting the number to the shader is the interesting half. A server-side mod cannot send a uniform
 * to a vanilla client's post effect, so it writes the number into the frame the shader reads — and it
 * has to be in the frame <em>before</em> the effect runs: 26.3's {@code GameRenderer.render} calls
 * {@code renderLevel()}, then {@code applyPostEffects()}, and only then {@code GuiRenderer.render()},
 * so nothing on the HUD (a title, the action bar) is on the target the effect samples. What is on it is
 * the held item, drawn inside {@code renderLevel} by {@code renderItemInHand}. So the meter rides the
 * weapon: every paint weapon model carries a one-pixel <em>data LED</em> whose faces are the only thing
 * in the item pipelines' atlases at alpha {@link nu.metacraft.rivals.pack.InkArt#LED_ALPHA}, tinted by
 * {@code custom_model_data} colour 0 — which this class writes:
 *
 * <ul>
 * <li>red 255 and green below 16 is the signature, which nothing else in the frame is for several flat
 *     pixels in a row (the probe checks two samples a step apart);</li>
 * <li>green's low nibble is the enemy team's index, which picks the ink colour;</li>
 * <li>blue is the amount, 0..255.</li>
 * </ul>
 *
 * <p>With no ink the value is {@link #IDLE}, a dark grey that matches no part of the signature, so the
 * shader sees nothing and the pip reads as an indicator that is simply off. The published value only
 * changes when the meter changes and at most every {@link #SEND_EVERY} ticks, because every change is an
 * item-slot sync to the client; {@link PaintWeapon#inventoryTick} is the one place it reaches the stacks.
 *
 * <p>Consequences worth knowing: in third person, with an empty hand, or with the weapon in the off-hand
 * out of view, there is no LED on the frame and therefore no ink, however full the meter is. The meter
 * itself keeps running, so the ink comes back the moment the weapon is in view again.
 */
public final class InkOnScreen {
	/** The most ink a screen can hold; the shader's amount byte is 0..{@code MAX}. */
	public static final int MAX = 255;
	/** Ink per point of damage taken from an enemy weapon. Four hearts of charger fills the screen. */
	public static final int PER_DAMAGE = 25;
	/** Ink a tick while standing in enemy paint. */
	public static final int STANDING_GAIN = 2;
	/** Ink that runs off on a tick nothing added any. */
	public static final int DECAY = 4;
	/** The fewest ticks between two changes of the published LED value for the same player. */
	public static final int SEND_EVERY = 2;
	/** The signature the shader looks for: red at full. */
	public static final int SIGNATURE_RED = 0xFF;
	/** The LED with nothing to say: a dark grey, which fails the red test and the green test both. */
	public static final int IDLE = 0x303030;

	/** One player's meter. {@code written} is the value the weapons are told to carry, {@link #IDLE} for none. */
	private static final class Meter {
		private int amount;
		private PaintColor color;
		private int written = IDLE;
		private long writtenAt;
		/** Whether anything added ink this tick; set by {@link #add}, cleared by {@link #tick}. */
		private boolean topped;

		private Meter(PaintColor color, long now) {
			this.color = color;
			// Far enough back that the first tick with ink on it writes at once, without underflowing.
			this.writtenAt = now - SEND_EVERY;
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
		if (!(victim instanceof Player player) || damage <= 0) return;
		add(player, by, Math.round(damage * PER_DAMAGE));
	}

	/** Ink for a tick spent standing in {@code enemy}'s paint. */
	public static void standing(Player player, PaintColor enemy) {
		add(player, enemy, STANDING_GAIN);
	}

	private static void add(Player player, PaintColor color, int ink) {
		if (ink <= 0) return;
		long now = player.level().getGameTime();
		Meter meter = METERS.computeIfAbsent(player.getUUID(), key -> new Meter(color, now));
		// The newest ink is the ink you see: a DATA hit on a screen full of IT turns it red.
		meter.color = color;
		meter.amount = Math.min(MAX, meter.amount + ink);
		meter.topped = true;
	}

	/**
	 * One tick of a player's meter: the decay on a tick nothing added to it, and a new published value if
	 * the one the weapons are carrying is out of date. Called from {@link nu.metacraft.rivals.PlayerTick}
	 * last, once the tick has had its chance to add ink.
	 */
	public static void tick(Player player, long now) {
		Meter meter = METERS.get(player.getUUID());
		if (meter == null) return;
		// Not in a match, not alive, not playing: no meter, and the LED goes dark.
		if (player.isSpectator() || !player.isAlive() || PaintColor.byTeam(player.getTeam()).isEmpty()) {
			clear(player);
			return;
		}
		if (!meter.topped) meter.amount = Math.max(0, meter.amount - DECAY);
		meter.topped = false;
		if (meter.amount == 0) {
			clear(player);
			return;
		}
		int value = led(meter.color, meter.amount);
		// Every change is an item-slot sync to the client, so the value is held still for a tick or two.
		if (value == meter.written || now < meter.writtenAt + SEND_EVERY) return;
		meter.written = value;
		meter.writtenAt = now;
	}

	/**
	 * The value the player's weapons should be carrying: the published meter, or {@link #IDLE} when there
	 * is nothing to say. {@link PaintWeapon#inventoryTick} puts it on the stacks, in the same place and
	 * the same way it keeps the tank's dye up to date — one writer, so a weapon that was stowed while the
	 * screen was full cannot come back out still carrying a live number.
	 */
	public static int ledFor(Player player) {
		Meter meter = METERS.get(player.getUUID());
		return meter == null ? IDLE : meter.written;
	}

	/**
	 * The LED's colour for a meter: red at full as the signature, green the enemy team's index, blue the
	 * amount. The item shader hands this straight to the frame, unlit and unmodulated, so what the probe
	 * reads back is this value byte for byte.
	 */
	public static int led(PaintColor color, int amount) {
		return SIGNATURE_RED << 16 | (color.ordinal() & 0xF) << 8 | Math.max(0, Math.min(MAX, amount));
	}

	/** Put {@code value} on {@code stack} if it is not already there. Returns whether anything changed. */
	public static boolean put(ItemStack stack, int value) {
		if (ledOf(stack) == value) return false;
		stack.set(DataComponents.CUSTOM_MODEL_DATA,
				new CustomModelData(List.of(), List.of(), List.of(), List.of(value)));
		return true;
	}

	/** The value a weapon is carrying, or {@link #IDLE} when it carries none. */
	public static int ledOf(ItemStack stack) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (data == null) return IDLE;
		Integer color = data.getColor(0);
		return color == null ? IDLE : color;
	}

	/** Wipe the meter; the weapons' LEDs go dark on their next inventory tick. */
	public static void clear(Player player) {
		METERS.remove(player.getUUID());
	}

	/** Drop a player's meter without touching their weapon: a disconnect, a server stop. */
	public static void forget(Player player) {
		METERS.remove(player.getUUID());
	}

	/** For tests: no meters anywhere. */
	public static void clearAll() {
		METERS.clear();
	}
}
