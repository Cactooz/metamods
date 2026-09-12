package nu.metacraft.rivals;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import nu.metacraft.rivals.gun.Ink;
import nu.metacraft.rivals.gun.PaintGun;
import nu.metacraft.rivals.paint.PaintBlock;
import nu.metacraft.rivals.paint.PaintDisplays;
import nu.metacraft.rivals.paint.Painter;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Per-player paint effects, every tick.
 *
 * <p>Sneaking in own-colour paint is squid form: small, quick, invisible, refilling, unable to shoot,
 * and able to swim up a wall it is pushing against if that wall is inked too. The size and speed come
 * from {@link SquidState}'s attribute modifiers rather than potion effects, so they are exact and do
 * not show up in the client's effect list; only invisibility is still a potion effect, because there
 * is no attribute for it.
 *
 * <p>Standing in another colour is a trap rather than an inconvenience: Slowness II, no jump at all,
 * and a point of damage every second (never the last one — enemy ink wears you down, it does not kill
 * you on its own). Potion effects here are short and topped back up to their full duration only once
 * they run low, so leaving the paint lets them run out within a second with no bookkeeping, and
 * vanilla isn't resyncing a fresh effect packet to the client every tick.
 */
public final class PlayerTick {
	private static final int EFFECT_TICKS = 15;
	private static final int TOPUP_EVERY = 5;
	/** Ticks between two drips of enemy-ink damage. */
	private static final int DRIP_EVERY = 20;
	private static final float DRIP_DAMAGE = 1.0f;
	/** Upward speed while swimming up an inked wall, blocks per tick. */
	private static final double WALL_SWIM_SPEED = 0.28;

	private PlayerTick() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = server.getTickCount();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) tick(player, now);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> SquidState.clearAll());
	}

	public static boolean isSquid(Player player) {
		return SquidState.isSquid(player);
	}

	/**
	 * The colour of the paint in the cell the player stands in, checked at two heights. Block paint and
	 * a wall/fence's display quads are keyed at the player's own feet cell, so that is checked first. A
	 * stair tread or bottom slab's quads are keyed one cell higher — {@link PaintDisplays#paint} stores
	 * them at {@code surface.relative(face)}, i.e. the cell above the tread — even though a player
	 * standing on that tread has {@code blockPosition()} equal to the tread's own cell, not the cell
	 * above it. So when the feet cell has nothing, the cell above is checked for quads only (a full
	 * paint block can't occupy the space a player's feet are standing in one cell below it).
	 *
	 * <p>That fallback only applies when the block at the feet cell is not a full cube. A player
	 * standing on a full block has their feet in the cell above it, and the cell above <em>that</em> is
	 * head height: paint on the wall beside a player's head is not paint they are standing in.
	 */
	public static @Nullable PaintColor paintUnder(Player player) {
		if (!(player.level() instanceof ServerLevel level)) return null;
		BlockPos feet = player.blockPosition();
		BlockState state = level.getBlockState(feet);
		if (state.getBlock() instanceof PaintBlock paint) return paint.color;
		PaintColor quads = PaintDisplays.of(level).colorAt(feet);
		if (quads != null) return quads;
		if (state.isCollisionShapeFullBlock(level, feet)) return null;
		return PaintDisplays.of(level).colorAt(feet.above());
	}

	/**
	 * Refresh {@code effect} to its full duration only when it is missing, weaker, or running low;
	 * re-adding it every tick regardless would make vanilla resend the effect packet every tick.
	 */
	private static void keep(Player player, Holder<MobEffect> effect, int amplifier) {
		MobEffectInstance current = player.getEffect(effect);
		if (current == null || current.getAmplifier() < amplifier || current.getDuration() < EFFECT_TICKS / 2) {
			player.addEffect(new MobEffectInstance(effect, EFFECT_TICKS, amplifier, true, false, false));
		}
	}

	public static void tick(Player player, long now) {
		// A spectator flies through the paint blocks they are standing in; giving them squid form,
		// slowness or damage for it is noise, and their gun (if any) is not usable anyway.
		if (player.isSpectator()) {
			SquidState.exit(player);
			SquidState.clearEnemyInk(player);
			return;
		}
		PaintColor under = paintUnder(player);
		Optional<PaintColor> own = PaintColor.byTeam(player.getTeam());
		boolean inOwn = under != null && own.isPresent() && under == own.get();
		boolean inEnemy = under != null && own.isPresent() && under != own.get();
		boolean squid = inOwn && player.isShiftKeyDown();
		if (squid) {
			SquidState.enter(player);
			keep(player, MobEffects.INVISIBILITY, 0);
			// Swimming up a wall is a shove, not an attribute: set the upward speed directly and mark
			// the movement dirty so the server tells the client about it this tick.
			if (player.horizontalCollision && paintedWallBeside(player, own.get())) {
				Vec3 velocity = player.getDeltaMovement();
				player.setDeltaMovement(velocity.x, WALL_SWIM_SPEED, velocity.z);
				player.hurtMarked = true;
			}
		} else {
			SquidState.exit(player);
		}
		if (inEnemy) {
			keep(player, MobEffects.SLOWNESS, 1);
			SquidState.applyEnemyInk(player);
			// Never the killing blow: enemy ink leaves you at one heart for someone else to finish.
			if (now % DRIP_EVERY == 0 && !player.isCreative() && player.getHealth() - DRIP_DAMAGE >= 1.0f
					&& player.level() instanceof ServerLevel level) {
				player.hurtServer(level, level.damageSources().magic(), DRIP_DAMAGE);
			}
		} else {
			SquidState.clearEnemyInk(player);
		}
		if (inOwn && now % TOPUP_EVERY == 0) {
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = player.getItemInHand(hand);
				if (stack.getItem() instanceof PaintGun) Ink.add(stack, squid ? 4 : 1);
			}
		}
	}

	/**
	 * Is there own-colour paint on a wall face the player is pushing against? Any of the four
	 * horizontal neighbours counts: the server does not know which way the collision was, and a squid
	 * pressed into a corner should climb either wall.
	 *
	 * <p>Paint on a wall face lives in the cell in front of that face, which is the player's own cell
	 * (feet or head): as a paint block with the face flag pointing back at the wall, or as display
	 * quads keyed at that same cell when the wall is not a full cube.
	 */
	static boolean paintedWallBeside(Player player, PaintColor own) {
		if (!(player.level() instanceof ServerLevel level)) return false;
		BlockPos feet = player.blockPosition();
		PaintDisplays displays = PaintDisplays.of(level);
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos wall = feet.relative(side);
			if (!Painter.paintable(level.getBlockState(wall))) continue;
			if (facing(level.getBlockState(feet), side, own) || facing(level.getBlockState(feet.above()), side, own)) return true;
			if (displays.colorAt(feet) == own) return true;
		}
		return false;
	}

	/** Is {@code cell} a paint block of {@code own} carrying a face that looks towards {@code side}? */
	private static boolean facing(BlockState cell, Direction side, PaintColor own) {
		return cell.getBlock() instanceof PaintBlock paint && paint.color == own
				&& cell.getValue(MultifaceBlock.getFaceProperty(side));
	}
}
