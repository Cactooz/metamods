package nu.metacraft.rivals;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
 * refilling, unable to shoot. Standing in another colour slows. Effects are short and re-applied each
 * tick, so leaving the paint ends them within a second with no bookkeeping.
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
	}

	public static boolean isSquid(Player player) {
		return SQUIDS.contains(player.getUUID());
	}

	/** The colour of the paint in the cell the player stands in (block paint or display quads), or null. */
	public static @Nullable PaintColor paintUnder(Player player) {
		if (!(player.level() instanceof ServerLevel level)) return null;
		BlockPos cell = player.blockPosition();
		BlockState state = level.getBlockState(cell);
		if (state.getBlock() instanceof PaintBlock paint) return paint.color;
		return PaintDisplays.of(level).colorAt(cell);
	}

	public static void tick(Player player, long now) {
		PaintColor under = paintUnder(player);
		Optional<PaintColor> own = PaintColor.byTeam(player.getTeam());
		boolean squid = under != null && own.isPresent() && under == own.get() && player.isShiftKeyDown();
		if (squid) {
			SQUIDS.add(player.getUUID());
			player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, EFFECT_TICKS, 0, true, false, false));
			player.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 1, true, false, false));
		} else {
			SQUIDS.remove(player.getUUID());
		}
		if (under != null && own.isPresent() && under != own.get()) {
			player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EFFECT_TICKS, 0, true, false, false));
		}
		if (under != null && own.isPresent() && under == own.get() && now % TOPUP_EVERY == 0) {
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = player.getItemInHand(hand);
				if (stack.getItem() instanceof PaintGun) Ink.add(stack, squid ? 4 : 1);
			}
		}
	}
}
