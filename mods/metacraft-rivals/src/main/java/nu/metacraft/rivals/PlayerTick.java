package nu.metacraft.rivals;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import nu.metacraft.rivals.gun.Ink;
import nu.metacraft.rivals.gun.PaintGun;
import nu.metacraft.rivals.paint.PaintBlock;
import nu.metacraft.rivals.paint.PaintDisplays;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player paint effects, every tick. Sneaking in own-colour paint is squid form: invisible, fast,
 * refilling, unable to shoot. Standing in another colour slows. Effects are short and topped back up to
 * their full duration only once they run low, so leaving the paint lets them run out within a second
 * with no bookkeeping, and vanilla isn't resyncing a fresh effect packet to the client every tick.
 */
public final class PlayerTick {
	private static final int EFFECT_TICKS = 15;
	private static final int TOPUP_EVERY = 5;
	private static final Set<UUID> SQUIDS = new HashSet<>();

	private PlayerTick() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = server.getTickCount();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) tick(player, now);
		});
		// The set is keyed by UUID and lives past the server it was filled from; a single-process
		// restart (a dev run, an integrated server) would otherwise start with everyone still a squid.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> SQUIDS.clear());
	}

	public static boolean isSquid(Player player) {
		return SQUIDS.contains(player.getUUID());
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
		// A spectator flies through the paint blocks they are "standing in"; giving them invisibility,
		// speed or slowness for it is noise, and their gun (if any) is not usable anyway.
		if (player.isSpectator()) {
			SQUIDS.remove(player.getUUID());
			return;
		}
		PaintColor under = paintUnder(player);
		Optional<PaintColor> own = PaintColor.byTeam(player.getTeam());
		boolean squid = under != null && own.isPresent() && under == own.get() && player.isShiftKeyDown();
		if (squid) {
			SQUIDS.add(player.getUUID());
			keep(player, MobEffects.INVISIBILITY, 0);
			keep(player, MobEffects.SPEED, 1);
		} else {
			SQUIDS.remove(player.getUUID());
		}
		if (under != null && own.isPresent() && under != own.get()) {
			keep(player, MobEffects.SLOWNESS, 0);
		}
		if (under != null && own.isPresent() && under == own.get() && now % TOPUP_EVERY == 0) {
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = player.getItemInHand(hand);
				if (stack.getItem() instanceof PaintGun) Ink.add(stack, squid ? 4 : 1);
			}
		}
	}
}
