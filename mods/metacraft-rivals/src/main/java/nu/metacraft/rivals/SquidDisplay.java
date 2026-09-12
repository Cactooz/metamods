package nu.metacraft.rivals;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The squid everyone else sees. Squid form is invisibility plus a half-size hitbox, which from the
 * outside is nothing at all: an enemy swimming past reads as empty floor, and the player themselves
 * has no way to tell a teammate's swim from a teammate's absence. So while the form is on, a blob of
 * team-coloured paint — the same {@code blob} model the thrown ball wears — rides the player's feet on
 * a Polymer item display, wide and flat, pointing the way they are going, stretched along a swim and
 * drawn out on a leap.
 *
 * <p>The blob is not sent to the squid itself: their own screen keeps the clean first-person view the
 * form is played from, and a blob drawn at their own feet would sit in the camera. There is no
 * per-viewer filter on an {@link EntityAttachment}, so the holder itself refuses to start watching its
 * own player — a viewer that never starts watching is never sent an entity to begin with.
 *
 * <p>A squid that is holding still <em>in</em> its own ink shows nothing at all: lying motionless in the
 * paint is how a squid hides, and a blob sitting on the surface would give the ambush away. The blob
 * comes back the moment it moves, leaps or climbs — anything that would leave a wake anyway.
 *
 * <p>The orientation and the stretch come from the movement {@link PlayerTick} measures between ticks
 * rather than from {@code getDeltaMovement()}, for the same reason everything else in the squid loop
 * does: for a real player the server's delta is a guess, and a blob oriented by it points the wrong
 * way. A squid that has stopped keeps whichever way it was last facing, because a blob that snaps back
 * to north the moment you let go of the keys reads as a bug.
 */
public final class SquidDisplay {
	/** The resting blob: wider than it is tall, because a squid lying in the ink is a puddle. */
	private static final float WIDE = 0.9f;
	private static final float TALL = 0.5f;
	/** How far above the feet the blob's centre sits. */
	private static final double LIFT = 0.25;
	/** How much of a stretch along the swim a block per tick of speed is worth, and the cap. */
	private static final float STRETCH_PER_SPEED = 1.4f;
	private static final float STRETCH_MAX = 0.6f;
	/** Above this much measured rise per tick the squid is leaping, and is drawn out upwards for it. */
	private static final double LEAP_SPEED = 0.2;
	private static final float LEAP_STRETCH = 0.5f;
	/** Below this much measured drop per tick onto the ground, a landing is just a landing. */
	private static final double LANDING_SPEED = -0.25;
	/** The landing pancake, and how many ticks it is held before the shape eases back. */
	private static final float LANDING_WIDE = 1.35f;
	private static final float LANDING_FLAT = 0.6f;
	private static final int LANDING_TICKS = 2;
	/** A tick of interpolation, so the blob glides between ticks instead of stepping. */
	private static final int INTERPOLATION = 1;
	/** Below this the measured movement has no direction worth turning to. */
	private static final double ORIENT_EPSILON = 1.0e-4;
	/** Below this much measured movement per tick, horizontal and vertical, the squid is holding still. */
	private static final double STILL_SPEED = 0.02;

	/** One blob per squid, by UUID: the same bookkeeping shape {@link SquidState} keeps. */
	private static final Map<UUID, Blob> BLOBS = new HashMap<>();

	private static final class Blob {
		private final ElementHolder holder;
		private final ItemDisplayElement element;
		private int color;
		/** Whether the blob is currently showing; a still squid in its own ink shows nothing. */
		private boolean shown = true;
		/** The last direction worth facing, kept for the ticks the squid holds still. */
		private Vec3 facing = new Vec3(0, 0, 1);
		/** Ticks left of the landing squash. */
		private int landing = 0;

		private Blob(ElementHolder holder, ItemDisplayElement element, int color) {
			this.holder = holder;
			this.element = element;
			this.color = color;
		}
	}

	/** A holder that will not show itself to one player: the squid wearing it. */
	private static final class OwnBlind extends ElementHolder {
		private final UUID owner;

		private OwnBlind(UUID owner) {
			this.owner = owner;
		}

		/** The rule, on its own, so a test can ask it without a connection to ask it through. */
		private boolean refuses(@Nullable UUID viewer) {
			return owner.equals(viewer);
		}

		@Override
		public boolean startWatching(ServerGamePacketListenerImpl connection) {
			if (connection.player != null && refuses(connection.player.getUUID())) return false;
			return super.startWatching(connection);
		}
	}

	private SquidDisplay() {}

	/** The holder riding {@code player}, or null when they are not showing a squid. Tests read this. */
	public static @Nullable ElementHolder holderOf(Player player) {
		Blob blob = BLOBS.get(player.getUUID());
		return blob == null ? null : blob.holder;
	}

	/**
	 * One tick of the blob: make it if it is missing, then point it along {@code moved} and shape it by
	 * how fast the player is going. {@code moved} is the movement measured this tick, so a zero one is a
	 * squid holding still rather than one with no information.
	 */
	public static void show(Player player, PaintColor color, Vec3 moved, boolean inOwnInk) {
		if (!(player.level() instanceof ServerLevel)) return;
		Blob blob = BLOBS.computeIfAbsent(player.getUUID(), uuid -> make(player, uuid, color));
		boolean hide = inOwnInk && new Vec3(moved.x, 0, moved.z).length() < STILL_SPEED
				&& Math.abs(moved.y) < STILL_SPEED;
		if (blob.color != color.rgb || blob.shown == hide) {
			blob.color = color.rgb;
			blob.shown = !hide;
			// An item display carrying nothing draws nothing, which is the whole of hiding: the element,
			// its entity and every watcher stay exactly as they are, so showing it again is one item
			// packet rather than a respawn.
			blob.element.setItem(hide ? ItemStack.EMPTY : blobStack(color));
		}
		if (hide) return;
		Vec3 along = new Vec3(moved.x, 0, moved.z);
		if (along.lengthSqr() > ORIENT_EPSILON * ORIENT_EPSILON) blob.facing = along.normalize();
		blob.element.setLeftRotation(new Quaternionf()
				.rotationY((float) Math.atan2(blob.facing.x, blob.facing.z)));
		blob.element.setScale(shape(player, blob, moved));
		blob.element.setInterpolationDuration(INTERPOLATION);
		blob.element.startInterpolationIfDirty();
	}

	/**
	 * The blob's size this tick. Swimming stretches it along its own length and narrows it across, the
	 * way the thrown ball stretches along its flight; a leap draws it upwards instead; and the tick it
	 * lands it pancakes for {@link #LANDING_TICKS}, which is what makes a hop read as having weight.
	 */
	private static Vector3f shape(Player player, Blob blob, Vec3 moved) {
		if (moved.y <= LANDING_SPEED && player.onGround()) blob.landing = LANDING_TICKS;
		if (blob.landing > 0) {
			blob.landing--;
			return new Vector3f(WIDE * LANDING_WIDE, TALL * LANDING_FLAT, WIDE * LANDING_WIDE);
		}
		if (moved.y > LEAP_SPEED) {
			float leap = 1 + LEAP_STRETCH;
			return new Vector3f(WIDE / leap, TALL * leap, WIDE / leap);
		}
		float stretch = (float) Math.min(STRETCH_MAX, STRETCH_PER_SPEED * new Vec3(moved.x, 0, moved.z).length());
		// Long along the swim (the model's local +Z, which the rotation above points that way), narrow
		// across it: what it gains in length it loses in width, so the blob keeps its volume.
		return new Vector3f(WIDE / (1 + stretch), TALL, WIDE * (1 + stretch));
	}

	private static Blob make(Player player, UUID uuid, PaintColor color) {
		ElementHolder holder = new OwnBlind(uuid);
		ItemDisplayElement element = new ItemDisplayElement(blobStack(color));
		element.setItemDisplayContext(ItemDisplayContext.FIXED);
		element.setOffset(new Vec3(0, LIFT, 0));
		element.setInterpolationDuration(INTERPOLATION);
		element.setTeleportDuration(1);
		element.setScale(new Vector3f(WIDE, TALL, WIDE));
		holder.addElement(element);
		EntityAttachment.ofTicking(holder, player);
		return new Blob(holder, element, color.rgb);
	}

	/** The blob model in the team colour — the paint ball's own display stack, at squid size. */
	private static ItemStack blobStack(PaintColor color) {
		ItemStack stack = new ItemStack(Items.STICK);
		stack.set(DataComponents.ITEM_MODEL, Rivals.id("blob"));
		stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color.rgb));
		return stack;
	}

	/**
	 * Take the blob down. Safe to call for a player who has none, which {@link PlayerTick} does every
	 * tick for everyone who is not a squid — the same shape as {@link SquidState#exit}.
	 */
	public static void hide(Player player) {
		Blob blob = BLOBS.remove(player.getUUID());
		if (blob != null) blob.holder.destroy();
	}

	/** Server stop: every blob goes, holders and all. The UUIDs would otherwise outlive the server. */
	public static void clearAll() {
		for (Blob blob : BLOBS.values()) blob.holder.destroy();
		BLOBS.clear();
	}

	/** Is {@code player}'s blob showing? False for a still squid in its own ink, and for no blob at all. */
	public static boolean isShown(Player player) {
		Blob blob = BLOBS.get(player.getUUID());
		return blob != null && blob.shown;
	}

	/**
	 * Would the blob riding {@code player} be kept from a viewer with this id? The rule
	 * {@code startWatching} applies, asked directly, because a game test has no second connection to
	 * watch through.
	 */
	public static boolean hiddenFrom(Player player, UUID viewer) {
		Blob blob = BLOBS.get(player.getUUID());
		return blob != null && blob.holder instanceof OwnBlind blind && blind.refuses(viewer);
	}

	/** How many blobs are riding players. Tests read this. */
	public static int showing() {
		return BLOBS.size();
	}
}
