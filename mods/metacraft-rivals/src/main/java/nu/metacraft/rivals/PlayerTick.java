package nu.metacraft.rivals;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import nu.metacraft.rivals.gun.Ink;
import nu.metacraft.rivals.gun.PaintWeapon;
import nu.metacraft.rivals.paint.Paint;
import nu.metacraft.rivals.paint.PaintDisplays;
import nu.metacraft.rivals.paint.Painter;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-player paint effects, every tick.
 *
 * <p>Sneaking in own-colour paint is squid form: small, quick, invisible, refilling, and unable to
 * shoot. Squid form also holds — it does not require paint under the feet — while the player is
 * beside a wall inked in their own colour: pushing into that wall climbs it, and easing off clings
 * to it instead of sliding back down, so a climb off the floor paint never drops the player mid-wall.
 * The size and speed come from {@link SquidState}'s attribute modifiers rather than potion effects,
 * so they are exact and do not show up in the client's effect list; only invisibility is still a
 * potion effect, because there is no attribute for it. Entering squid form from a stand is a dive: a
 * horizontal shove along the player's look direction and a quiet splash, gated by a short per-player
 * cooldown so it fires once per dive rather than every tick spent in the paint. A swimming squid
 * leaves a wake — a couple of ink specks at its feet every tick it is actually moving, a soft swim
 * note every six — and the dive throws a ring of them; a squid that has stopped leaves nothing, so
 * the trail reads as movement rather than as a permanent marker saying "someone is here".
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
	private static final double WALL_SWIM_SPEED = 0.42;
	/** How far the player's box may sit off a wall's plane and still count as pressed against it. */
	private static final double WALL_REACH = 0.15;
	/** Horizontal nudge over the lip on the tick the climbed wall runs out above the player's head. */
	private static final double LEDGE_HOP = 0.25;
	/** Horizontal push, along the look direction, on the tick squid form is entered. */
	private static final double DIVE_SURGE_SPEED = 0.45;
	/** No repeat surge for a re-entry (e.g. a brief unshift) within this many ticks of the last one. */
	private static final int DIVE_SURGE_COOLDOWN = 10;
	/** How often a player's ovve is checked against their team. Once a second is plenty for getting dressed. */
	private static final int OVVE_EVERY = 20;

	/** Below this many blocks per tick of horizontal movement a squid is holding still, not swimming. */
	private static final double RIPPLE_SPEED = 0.05;
	/** Ink crumbs per swimming tick. */
	private static final int RIPPLE_PARTICLES = 2;
	/** Ticks between two swim notes. */
	private static final int SWIM_SOUND_EVERY = 6;
	/** Specks thrown in a ring on the dive, and how far out they land. */
	private static final int DIVE_RING_PARTICLES = 8;
	private static final double DIVE_RING_RADIUS = 0.5;

	/** Server tick of each player's last dive surge, so a flicker in and out of squid form does not
	 * re-trigger it every tick. Cleared alongside the squid bookkeeping on disconnect and server stop. */
	private static final Map<UUID, Long> LAST_DIVE = new HashMap<>();
	/** Where each squid was last tick, because a real player's server-side delta is not its speed. */
	private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();

	private PlayerTick() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = server.getTickCount();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) tick(player, now);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			SquidState.clearAll();
			LAST_DIVE.clear();
			LAST_POS.clear();
		});
		// A player who logs out mid-squid (or standing in enemy ink) is never ticked again, so nothing
		// would ever take the state off them: the UUID would stay in the squid set, and a rejoin would
		// report a squid whose attributes died with the old entity. Same tidy-up the spectator branch does.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			SquidState.exit(handler.getPlayer());
			SquidState.clearEnemyInk(handler.getPlayer());
			LAST_DIVE.remove(handler.getPlayer().getUUID());
			LAST_POS.remove(handler.getPlayer().getUUID());
		});
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
	 *
	 * <p>Paint under the feet means the cell's down face, the one lying on the floor the player stands
	 * on. A cell whose only paint is on a wall face is paint beside them, not under them — that is
	 * {@link #paintedWallBeside}'s business, and it is what holds squid form on during a wall climb.
	 * Display quads obey the same rule from the other side: their cell records the face of the
	 * <em>surface</em> they cover, so floor quads are the ones painted on a surface's {@link
	 * Direction#UP} face — quads on the side of a pane are a wall, not a floor, and must not count as
	 * paint underfoot.
	 */
	public static @Nullable PaintColor paintUnder(Player player) {
		if (!(player.level() instanceof ServerLevel level)) return null;
		BlockPos feet = player.blockPosition();
		BlockState state = level.getBlockState(feet);
		if (state.getBlock() instanceof Paint paint && (paint.faceMask(state) & 1 << Direction.DOWN.ordinal()) != 0) return paint.color();
		PaintDisplays displays = PaintDisplays.of(level);
		PaintColor quads = floorQuads(displays, feet);
		if (quads != null) return quads;
		if (state.isCollisionShapeFullBlock(level, feet)) return null;
		return floorQuads(displays, feet.above());
	}

	/**
	 * The colour of the display quads lying face-up in {@code cell}, or null. {@link PaintDisplays}
	 * keys a cell by the surface face its quads cover, so {@link Direction#UP} is the floor case: the
	 * top of a slab, a stair tread or a fence post. Any other face is paint on a wall or a ceiling.
	 */
	private static @Nullable PaintColor floorQuads(PaintDisplays displays, BlockPos cell) {
		return displays.faceAt(cell) == Direction.UP ? displays.colorAt(cell) : null;
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
		if (now % OVVE_EVERY == 0) wearYourColours(player);
		PaintColor under = paintUnder(player);
		Optional<PaintColor> own = PaintColor.byTeam(player.getTeam());
		boolean inOwn = under != null && own.isPresent() && under == own.get();
		boolean inEnemy = under != null && own.isPresent() && under != own.get();
		// A climb off the floor paint must not drop squid form mid-wall: without this, the moment the
		// player is lifted off the ground `inOwn` goes false, squid form ends, and the wall swim only
		// ever lasts the one tick that started it.
		boolean wallBeside = own.isPresent() && paintedWallBeside(player, own.get());
		boolean squid = (inOwn || wallBeside) && player.isShiftKeyDown();
		boolean wasSquid = SquidState.isSquid(player);
		if (squid) {
			SquidState.enter(player);
			if (!wasSquid) diveSurge(player, now);
			keep(player, MobEffects.INVISIBILITY, 0);
			wake(player, own.get(), now);
			// Swimming up a wall is a shove, not an attribute: set the velocity directly and mark the
			// movement dirty so the server tells the client about it this tick. Pushing into the wall
			// climbs it; otherwise the squid clings rather than sliding back down.
			if (wallBeside) {
				Vec3 velocity = player.getDeltaMovement();
				Direction climbing = paintedWallToward(player, own.get(), moveIntent(player));
				if (climbing != null) {
					// Nothing left to press into above the head means the wall has run out: a small push
					// over the lip, or the squid hangs at the top of the climb instead of topping out.
					double lip = topsOut(player, climbing) ? LEDGE_HOP : 0.0;
					player.setDeltaMovement(velocity.x + climbing.getStepX() * lip, WALL_SWIM_SPEED,
							velocity.z + climbing.getStepZ() * lip);
				} else {
					player.setDeltaMovement(velocity.x, Math.max(velocity.y, 0.0), velocity.z);
				}
				player.hurtMarked = true;
				player.resetFallDistance();
			}
		} else {
			SquidState.exit(player);
			LAST_POS.remove(player.getUUID());
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
		if ((inOwn || wallBeside) && now % TOPUP_EVERY == 0) {
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = player.getItemInHand(hand);
				if (stack.getItem() instanceof PaintWeapon) Ink.add(stack, squid ? 4 : 1);
			}
		}
	}

	/**
	 * The ovve picks the team. Metacraft's ovvar mod dresses players in chapter overalls, and the arena
	 * should read the same way the campus does: put on the DATA ovve and you are on DATA, with no
	 * command to run. Matched on the item's registry id by {@link OvveTeams}, so nothing here depends on
	 * ovvar being installed; a player wearing no ovve keeps whatever team they had, because taking
	 * someone off their team for changing trousers would be worse than leaving them on it.
	 *
	 * <p>{@link RivalsCommands#setupTeams} is idempotent and creates both teams, so joining works on a
	 * server where nobody has run {@code /rivals setup} yet.
	 */
	private static void wearYourColours(Player player) {
		if (!(player.level() instanceof ServerLevel level)) return;
		Optional<PaintColor> ovve = OvveTeams.worn(player);
		if (ovve.isEmpty()) return;
		PaintColor color = ovve.get();
		if (PaintColor.byTeam(player.getTeam()).orElse(null) == color) return;
		RivalsCommands.setupTeams(level.getServer());
		ServerScoreboard board = level.getScoreboard();
		PlayerTeam team = board.getPlayerTeam(color.id);
		if (team == null) return;
		board.addPlayerToTeam(player.getScoreboardName(), team);
		if (player instanceof ServerPlayer server && server.connection != null) {
			server.sendSystemMessage(Component.literal("Your ovve puts you on " + color.displayName), true);
		}
	}

	/**
	 * The swimming squid's wake, once a tick. The speed is measured between this tick's position and
	 * last tick's rather than read off {@code getDeltaMovement()}: for a real player the server's delta
	 * comes from the move packets and is zero most ticks, so a squid that is plainly moving would leave
	 * nothing. The first tick of a swim has no previous position and so leaves nothing either, which is
	 * a tick, not a problem.
	 */
	private static void wake(Player player, PaintColor own, long now) {
		if (!(player.level() instanceof ServerLevel level)) return;
		Vec3 at = player.position();
		Vec3 last = LAST_POS.put(player.getUUID(), at);
		Vec3 moved = last == null ? Vec3.ZERO : at.subtract(last);
		if (ripples(level, player, own, moved) > 0 && now % SWIM_SOUND_EVERY == 0) {
			level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_SWIM, SoundSource.PLAYERS, 0.25f, 1.6f);
		}
	}

	/**
	 * Ink specks at the squid's feet, if it is moving horizontally at all. Returns how many were sent —
	 * the only thing a server-side test can see, since particles leave no trace in the level.
	 *
	 * <p>Everyone gets them, the squid included: three small specks down at foot level are under the
	 * camera of a half-height squid, and leaving your own wake out is what makes squid form feel like
	 * nothing is happening.
	 */
	public static int ripples(ServerLevel level, Player player, PaintColor color, Vec3 velocity) {
		if (velocity.horizontalDistance() <= RIPPLE_SPEED) return 0;
		// A little upward speed so the crumbs hop out of the ink and fall back rather than sitting on it.
		level.sendParticles(Painter.crumbs(color), player.getX(), player.getY() + 0.05,
				player.getZ(), RIPPLE_PARTICLES, 0.35, 0.02, 0.35, 0.05);
		return RIPPLE_PARTICLES;
	}

	/**
	 * A snappy dive: the tick squid form is entered from not-squid, push the player along their look
	 * direction and play a quiet splash. Cooldown-gated on {@link #LAST_DIVE} so a flicker in and out
	 * of squid form (e.g. a one-tick unshift at the edge of the paint) does not surge every re-entry.
	 */
	private static void diveSurge(Player player, long now) {
		Long last = LAST_DIVE.get(player.getUUID());
		if (last != null && now - last < DIVE_SURGE_COOLDOWN) return;
		LAST_DIVE.put(player.getUUID(), now);
		Vec3 look = player.getLookAngle();
		player.push(look.x * DIVE_SURGE_SPEED, 0, look.z * DIVE_SURGE_SPEED);
		player.hurtMarked = true;
		if (player.level() instanceof ServerLevel level) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.4f, 1.5f);
			// A ring of ink thrown outwards, so the dive lands with a splat rather than a shove.
			PaintColor.byTeam(player.getTeam()).ifPresent(color -> {
				for (int i = 0; i < DIVE_RING_PARTICLES; i++) {
					double angle = i * 2.0 * Math.PI / DIVE_RING_PARTICLES;
					level.sendParticles(Painter.crumbs(color),
							player.getX() + Math.cos(angle) * DIVE_RING_RADIUS, player.getY() + 0.05,
							player.getZ() + Math.sin(angle) * DIVE_RING_RADIUS, 1, 0.0, 0.0, 0.0, 0.0);
				}
			});
		}
	}

	/**
	 * Is there own-colour paint on a wall face beside the player? Any of the four horizontal neighbours
	 * counts — this is the cling and the reason squid form holds off the floor paint, so which wall it
	 * is does not matter here; {@link #paintedWallToward} is the directed version that climbs.
	 *
	 * <p>Paint on a wall face lives in the cell in front of that face, which is the player's own cell
	 * (feet or head): as a paint block with the face flag pointing back at the wall, or as display
	 * quads keyed at that same cell when the wall is not a full cube. A quad-painted floor slab's
	 * quads are keyed at that same feet cell too (one cell above the tread), so the colour match alone
	 * is not enough — the quads must also carry the horizontal face pointing from the wall back at the
	 * player, i.e. {@code side.getOpposite()}, or a squid standing on its own paint would climb any
	 * paintable neighbour regardless of whether that neighbour is inked.
	 *
	 * <p>The head-height paint is matched against the neighbour at head height rather than the one beside
	 * the ankles: paint a cell up belongs to whatever wall is a cell up, and a shoulder-high ledge with
	 * air below it is still a wall to climb.
	 */
	static boolean paintedWallBeside(Player player, PaintColor own) {
		return paintedWall(player, own, Vec3.ZERO) != null;
	}

	/**
	 * The inked wall the player is actually climbing: one they are both asking to move into and pressed
	 * up against, or null. {@code move} is the intended direction from {@link #moveIntent}; an empty
	 * one is a cling rather than a climb, so it never picks a wall.
	 */
	static @Nullable Direction paintedWallToward(Player player, PaintColor own, Vec3 move) {
		// Not a guard the shared scan can make: an empty move there means the undirected cling scan,
		// which answers with whatever wall is beside the player.
		return move.lengthSqr() < 1.0E-4 ? null : paintedWall(player, own, move);
	}

	/**
	 * Which way the player is asking to move, as a unit horizontal vector, or {@link Vec3#ZERO} when
	 * they are asking for nothing.
	 *
	 * <p>This reads the client's own key state rather than the movement the server saw. A player
	 * walking into a wall has their delta clipped client-side and sends one of about zero, so the
	 * server's own {@code move()} never reports a horizontal collision on that player: testing
	 * {@code horizontalCollision} here only ever climbed the single block squid form's taller step
	 * height carried the player over. Only a {@link ServerPlayer} has client input; any other player
	 * (a fake one) is treated as asking for nothing and can still cling.
	 */
	static Vec3 moveIntent(Player player) {
		if (!(player instanceof ServerPlayer server)) return Vec3.ZERO;
		Input input = server.getLastClientInput();
		double forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
		double strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
		if (forward == 0 && strafe == 0) return Vec3.ZERO;
		// The rotation vanilla's Entity.getInputVector does: yaw 0 faces +Z, and the strafe axis is
		// positive to the left.
		float yaw = player.getYRot() * ((float) Math.PI / 180f);
		double sin = Mth.sin(yaw);
		double cos = Mth.cos(yaw);
		return new Vec3(strafe * cos - forward * sin, 0, forward * cos + strafe * sin).normalize();
	}

	/**
	 * The shared scan. With an empty {@code move} every horizontal neighbour counts, at any distance
	 * the player's own cell can reach — that is the cling, and the test that keeps squid form on. With a
	 * direction, only a wall the player is pushing into (within 60° of it, i.e. {@code dot > 0.5}) and
	 * hugging counts.
	 */
	private static @Nullable Direction paintedWall(Player player, PaintColor own, Vec3 move) {
		if (!(player.level() instanceof ServerLevel level)) return null;
		boolean directed = move.lengthSqr() >= 1.0E-4;
		BlockPos feet = player.blockPosition();
		BlockPos head = feet.above();
		PaintDisplays displays = PaintDisplays.of(level);
		// None of these four vary with the direction, and three are chunk lookups: read them once.
		BlockState atFeet = level.getBlockState(feet);
		BlockState atHead = level.getBlockState(head);
		PaintColor quadColor = displays.colorAt(feet);
		Direction quadFace = displays.faceAt(feet);
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (directed && (move.x * side.getStepX() + move.z * side.getStepZ() <= 0.5
					|| !pressedAgainst(player, feet.relative(side), side))) {
				continue;
			}
			if (Painter.paintable(level.getBlockState(feet.relative(side)))) {
				if (facing(atFeet, side, own)) return side;
				if (quadColor == own && quadFace == side.getOpposite()) return side;
			}
			if (facing(atHead, side, own) && Painter.paintable(level.getBlockState(head.relative(side)))) return side;
		}
		return null;
	}

	/** Is the player's box within {@link #WALL_REACH} of {@code wall}'s near plane, or already inside it? */
	private static boolean pressedAgainst(Player player, BlockPos wall, Direction side) {
		AABB box = player.getBoundingBox();
		double gap = switch (side) {
			case EAST -> wall.getX() - box.maxX;
			case WEST -> box.minX - (wall.getX() + 1);
			case SOUTH -> wall.getZ() - box.maxZ;
			case NORTH -> box.minZ - (wall.getZ() + 1);
			default -> Double.POSITIVE_INFINITY;
		};
		return gap <= WALL_REACH;
	}

	/**
	 * Has the climbed wall run out? The cell two above the player's feet is still beside their head, so
	 * the question is about the one above <em>that</em>: no collision there means the next tick of the
	 * climb has nothing left to press into. Asking a block lower fires the hop while the wall is still
	 * there and shoves the squid off it. A pane or fence still counts as wall, so a climb up one is not
	 * cut short either.
	 */
	private static boolean topsOut(Player player, Direction side) {
		if (!(player.level() instanceof ServerLevel level)) return false;
		BlockPos above = player.blockPosition().above(3).relative(side);
		return level.getBlockState(above).getCollisionShape(level, above).isEmpty();
	}

	/** Is {@code cell} a paint block of {@code own} carrying a face that looks towards {@code side}? */
	private static boolean facing(BlockState cell, Direction side, PaintColor own) {
		return cell.getBlock() instanceof Paint paint && paint.color() == own
				&& (paint.faceMask(cell) & 1 << side.ordinal()) != 0;
	}
}
