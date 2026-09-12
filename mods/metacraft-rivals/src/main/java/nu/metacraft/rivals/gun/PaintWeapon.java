package nu.metacraft.rivals.gun;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.PlayerTick;
import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.paint.Painter;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every paint weapon, in one item class parameterised by a {@link Weapon}. Right-click throws paint in
 * the colour of the holder's vanilla team; no team, no shot. Vanilla clients keep sending use packets
 * while the button is held, so the item cooldown is the fire rate. Clients see a stand-in vanilla item
 * wearing our 3D model; the model's tank is dye-tinted, and each inventory tick writes the holder's
 * team colour into the server-side stack as that dye, so every viewer sees the weapon in its holder's
 * colour.
 *
 * <p>Only the slosher swings the arm: it is a bucket, and the throw reads as one. The other three
 * return {@link InteractionResult#CONSUME}, which takes the click without animating the hand — a
 * four-tick swing loop on the shooter and sprayer looks like a stutter, not like firing.
 */
public final class PaintWeapon extends Item implements PolymerItem {
	private static final Map<Weapon, PaintWeapon> ITEMS = new EnumMap<>(Weapon.class);
	/** World up, for the barrel offset: right is look × up. */
	private static final Vec3 UP = new Vec3(0, 1, 0);

	private final Weapon weapon;

	public PaintWeapon(Weapon weapon, Properties properties) {
		super(properties);
		this.weapon = weapon;
	}

	public static void register() {
		for (Weapon weapon : Weapon.values()) {
			Identifier id = Rivals.id(weapon.id);
			ITEMS.put(weapon, Registry.register(BuiltInRegistries.ITEM, id,
					new PaintWeapon(weapon, new Item.Properties().stacksTo(1).setId(ResourceKey.create(Registries.ITEM, id)))));
		}
	}

	/** The registered item for a weapon; null until {@link #register()} has run. */
	public static PaintWeapon of(Weapon weapon) {
		return ITEMS.get(weapon);
	}

	public Weapon weapon() {
		return weapon;
	}

	/**
	 * One of every weapon, dropped at the player's feet if the inventory is full. Returns how many went
	 * into the inventory — not how many were made — so a full inventory reports what it actually took.
	 */
	public static int giveKit(Player player) {
		int given = 0;
		for (Weapon weapon : Weapon.values()) {
			ItemStack stack = new ItemStack(of(weapon));
			if (player.getInventory().add(stack)) {
				given++;
			} else {
				player.drop(stack, false);
			}
		}
		return given;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
		ItemStack gun = player.getItemInHand(hand);
		Optional<PaintColor> ready = ready(serverLevel, player, gun); // team, refill, squid
		if (ready.isEmpty()) return InteractionResult.FAIL;
		PaintColor color = ready.get();
		// A tank that cannot cover the shot is as good as empty: one ink must not buy a fifteen-ink slosh.
		if (Ink.get(gun) < weapon.inkPerShot) {
			outOfInk(serverLevel, player, gun);
			return InteractionResult.FAIL;
		}
		// The charger spends nothing on the press: the shot, its ink and its cooldown all wait for the
		// release, which is what makes the hold a charge rather than a delayed trigger.
		if (weapon == Weapon.CHARGER) {
			player.startUsingItem(hand);
			return InteractionResult.CONSUME;
		}
		fire(serverLevel, player, color);
		feel(serverLevel, player, color);
		Ink.add(gun, -weapon.inkPerShot);
		player.getCooldowns().addCooldown(gun, weapon.cooldownTicks);
		if (player instanceof ServerPlayer serverPlayer) InkHud.show(serverPlayer);
		return weapon == Weapon.SLOSHER ? InteractionResult.SUCCESS_SERVER : InteractionResult.CONSUME;
	}

	/** Throw this weapon's paint from the shooter's eyes along their view. */
	public void fire(ServerLevel level, Player player, PaintColor color) {
		switch (weapon) {
			case SHOOTER -> {
				PaintBall ball = new PaintBall(level, player, color, Weapon.SHOOTER_BOUNCES, 0);
				ball.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f, weapon.velocity, weapon.inaccuracy);
				level.addFreshEntity(ball);
			}
			// Three droplets down one barrel: the spread is what separates them, and each paints only the
			// face it lands on, so a held trigger reads as a cone of mist rather than three fat blobs.
			case SPRAYER -> {
				for (int i = 0; i < Weapon.SPRAYER_DROPLETS; i++) {
					PaintBall drop = new PaintBall(level, player, color, 0, Weapon.SPRAYER_LIFETIME);
					drop.setSplatRadius(0);
					drop.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f, weapon.velocity, weapon.inaccuracy);
					level.addFreshEntity(drop);
				}
			}
			// A deliberate fan, not a spread: fixed yaw offsets so the four arcs land side by side every time.
			case SLOSHER -> {
				for (float offset : Weapon.SLOSHER_FAN) {
					PaintBall ball = new PaintBall(level, player, color, 0, 0);
					ball.setSplatRadius(Weapon.SLOSHER_SPLAT_RADIUS);
					ball.setGravity(Weapon.SLOSHER_GRAVITY);
					ball.shootFromRotation(player, player.getXRot() + Weapon.SLOSHER_PITCH, player.getYRot() + offset,
							0.0f, weapon.velocity, weapon.inaccuracy);
					level.addFreshEntity(ball);
				}
			}
			case CHARGER -> {} // the charger fires on release; see releaseUsing
		}
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return weapon == Weapon.CHARGER ? Weapon.CHARGE_MAX_TICKS : 0;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		// The client item is a spyglass, so the spyglass animation is also the scope: holding zooms.
		return weapon == Weapon.CHARGER ? ItemUseAnimation.SPYGLASS : ItemUseAnimation.NONE;
	}

	/**
	 * The charger's shot, fired when the hold ends. How long the button was down is the whole weapon:
	 * under {@link Weapon#MIN_CHARGE_TICKS} it was a tap and nothing happens (no ink, no cooldown — a
	 * mis-click must not cost anything), and from there to {@link Weapon#CHARGE_FULL_TICKS} the charge scales
	 * range, ink and kick together. The shot itself is hitscan: one clip along the view, a line of paint
	 * on the floor under it, and a splash where it stops — under the feet of whoever was standing in the
	 * way, if anyone was, and otherwise on the block face it ran into.
	 */
	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		// Only the server has the paint, the tank and the scoreboard; the client never fires this.
		if (weapon != Weapon.CHARGER || !(level instanceof ServerLevel serverLevel) || !(entity instanceof Player player)) return false;
		int held = getUseDuration(stack, entity) - timeLeft;
		if (held < Weapon.MIN_CHARGE_TICKS) {
			// A tap costs nothing, which also means it gives no feedback at all: without a word the weapon
			// reads as broken to anyone clicking it the way the other three are clicked.
			actionBar(player, Component.literal("Hold to charge").withStyle(ChatFormatting.GRAY));
			return false;
		}
		float charge = Math.min(1.0f, held / (float) Weapon.CHARGE_FULL_TICKS);
		Optional<PaintColor> ready = ready(serverLevel, player, stack);
		if (ready.isEmpty()) return false;
		PaintColor color = ready.get();
		int cost = Math.round(Weapon.CHARGE_BASE_COST + Weapon.CHARGE_EXTRA_COST * charge);
		if (Ink.get(stack) < cost) {
			outOfInk(serverLevel, player, stack);
			return false;
		}
		double range = Weapon.CHARGE_BASE_RANGE + Weapon.CHARGE_EXTRA_RANGE * charge;
		Vec3 from = entity.getEyePosition();
		Vec3 reach = entity.getLookAngle().scale(range);
		Vec3 to = from.add(reach);
		BlockHitResult hit = serverLevel.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
		boolean struck = hit.getType() == HitResult.Type.BLOCK;
		Vec3 end = struck ? hit.getLocation() : to;
		// Anyone standing in the way stops the line where they are: scanned only as far as the block hit,
		// so a hit that comes back is by construction the nearer of the two. Paint goes under their feet,
		// the same shape a ball's entity hit makes — a charger line that passed through a player and
		// painted the wall behind them read as a miss.
		AABB along = entity.getBoundingBox().expandTowards(reach).inflate(1.0);
		EntityHitResult inTheWay = ProjectileUtil.getEntityHitResult(serverLevel, entity, from, end, along,
				// isPickable, so a dropped item, an XP orb or someone else's paint ball in flight does not
				// stop the line: those are not what a charger shot is aimed at.
				candidate -> candidate != entity && candidate.isAlive() && candidate.isPickable() && !candidate.isSpectator(), 0.0f);
		if (inTheWay != null) end = inTheWay.getLocation();
		int painted = Painter.line(serverLevel, from, end, color, entity);
		if (inTheWay != null) {
			BlockPos below = inTheWay.getEntity().blockPosition().below();
			painted += Painter.splash(serverLevel, end, below, Direction.UP, color, serverLevel.getRandom(), entity);
		} else if (struck) {
			painted += Painter.splash(serverLevel, end, hit.getBlockPos(), hit.getDirection(), color, serverLevel.getRandom(), entity);
		}
		Rivals.LOGGER.debug("charger: charge {}, range {}, {} cells painted", charge, range, painted);
		Ink.add(stack, -cost);
		player.getCooldowns().addCooldown(stack, weapon.cooldownTicks);
		// A half charge should not buck like a full one, so the kick rides the charge.
		Recoil.kick(player, weapon.kickPitch * charge);
		muzzle(serverLevel, player, color);
		if (player instanceof ServerPlayer serverPlayer) InkHud.show(serverPlayer);
		return true;
	}

	/**
	 * The checks every shot shares: a team to paint for, a tank that is not mid-refill, and hands rather
	 * than fins. Says why when it refuses, and hands back the colour it found: a shot that is allowed is
	 * exactly a shot that has one, so looking the team up again afterwards was a second call that could
	 * only have failed if this one had.
	 */
	private Optional<PaintColor> ready(ServerLevel level, Player player, ItemStack gun) {
		Optional<PaintColor> color = PaintColor.byTeam(player.getTeam());
		if (color.isEmpty()) {
			actionBar(player, Component.literal("Join a team first: /team join " + PaintColor.values()[0].id)
					.withStyle(ChatFormatting.RED));
			return Optional.empty();
		}
		long now = level.getServer().getTickCount();
		Ink.finishIfDue(gun, now);
		if (Ink.isRefilling(gun, now)) return Optional.empty();
		if (isSquid(player)) {
			actionBar(player, Component.literal("Can't shoot in squid form").withStyle(ChatFormatting.RED));
			return Optional.empty();
		}
		return color;
	}

	/** An empty tank: start the refill, and hold the gun on cooldown until it is done. */
	private static void outOfInk(ServerLevel level, Player player, ItemStack gun) {
		Ink.startRefill(gun, level.getServer().getTickCount());
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.8f, 0.9f);
		player.getCooldowns().addCooldown(gun, Ink.REFILL_TICKS);
		if (player instanceof ServerPlayer serverPlayer) InkHud.show(serverPlayer);
	}

	static void actionBar(Player player, Component text) {
		if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) serverPlayer.sendSystemMessage(text, true);
	}

	/** Squid form (sneaking on own paint) can't shoot. */
	public static boolean isSquid(Player player) {
		return PlayerTick.isSquid(player);
	}

	/** The chunk of a shot: camera kick, a nudge back, a muzzle burst in the team colour, layered sounds. */
	void feel(ServerLevel level, Player shooter, PaintColor color) {
		Recoil.kick(shooter, weapon.kickPitch);
		muzzle(level, shooter, color);
	}

	/** How far a muzzle burst is worth sending; past this nobody reads it as a shot anyway. */
	private static final double MUZZLE_RANGE = 32.0;
	/** The burst everyone but the shooter sees, at eye + look × this. */
	private static final double MUZZLE_REACH = 0.9;
	/** The shooter's own, smaller burst: at the barrel tip, off to the right of the view and below it. */
	private static final double BARREL_REACH = 1.4;
	private static final double BARREL_RIGHT = 0.3;
	private static final double BARREL_DROP = 0.25;

	/** Everything about a shot but the camera kick: the nudge back, the burst of colour, the layered sounds. */
	void muzzle(ServerLevel level, Player shooter, PaintColor color) {
		Vec3 look = shooter.getLookAngle();
		shooter.push(-look.x * 0.06, 0, -look.z * 0.06);
		shooter.hurtMarked = true;
		// The full burst is for everyone else. Ink crumbs at the shooter's own eyes hang in front of their
		// camera for the whole of a held trigger and clog the first-person view, so the shooter gets a
		// couple at the barrel tip instead — off the centre of the screen, where a muzzle is.
		Vec3 muzzle = shooter.getEyePosition().add(look.scale(MUZZLE_REACH));
		BlockParticleOption dust = Painter.crumbs(color);
		for (ServerPlayer viewer : level.players()) {
			if (viewer == shooter || viewer.position().distanceToSqr(muzzle) > MUZZLE_RANGE * MUZZLE_RANGE) continue;
			level.sendParticles(viewer, dust, false, false, muzzle.x, muzzle.y, muzzle.z, 5, 0.1, 0.1, 0.1, 0.02);
		}
		if (shooter instanceof ServerPlayer self) {
			Vec3 across = look.cross(UP);
			Vec3 right = across.lengthSqr() < 1.0e-6 ? Vec3.ZERO : across.normalize(); // straight up or down: no side
			Vec3 barrel = shooter.getEyePosition()
					.add(look.scale(BARREL_REACH))
					.add(right.scale(BARREL_RIGHT))
					.subtract(UP.scale(BARREL_DROP));
			level.sendParticles(self, dust, false, false,
					barrel.x, barrel.y, barrel.z, 2, 0.05, 0.05, 0.05, 0.0);
		}
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.7f, 0.7f);
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.SLIME_BLOCK_PLACE, SoundSource.PLAYERS, 0.5f, 1.4f);
		// A bucketful wants weight under the snowball throw; a low slime step is that weight.
		if (weapon == Weapon.SLOSHER) {
			level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.SLIME_BLOCK_STEP, SoundSource.PLAYERS, 0.9f, 0.6f);
		}
	}

	/**
	 * The tank rule for a server-side weapon stack, in one place: dyed with the team's paint colour (the
	 * model's tank reads that dye), undyed for no team or a team that is none of ours. Polymer copies
	 * the dye onto the client stack, so every viewer sees the holder's colour.
	 */
	public static ItemStack withTankColor(ItemStack stack, @Nullable PlayerTeam team) {
		Optional<PaintColor> color = PaintColor.byTeam(team);
		if (color.isPresent()) {
			stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color.get().rgb));
		} else {
			stack.remove(DataComponents.DYED_COLOR);
		}
		return stack;
	}

	/**
	 * Keep the tank dye in step with the holder's team. Compared before writing, because setting a
	 * component re-syncs the stack to everyone who can see it and this runs every tick.
	 */
	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
		Ink.finishIfDue(stack, level.getServer().getTickCount());
		if (!(entity instanceof LivingEntity holder)) return; // a dropped weapon keeps the dye it had
		PlayerTeam team = holder.getTeam();
		DyedItemColor wanted = PaintColor.byTeam(team).map(color -> new DyedItemColor(color.rgb)).orElse(null);
		if (Objects.equals(stack.get(DataComponents.DYED_COLOR), wanted)) return;
		withTankColor(stack, team);
	}

	@Override
	public Item getPolymerItem(ItemStack stack, PacketContext context) {
		return weapon == Weapon.CHARGER ? Items.SPYGLASS : Items.WARPED_FUNGUS_ON_A_STICK;
	}

	@Override
	public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
		return Rivals.id(weapon.id);
	}

	@Override
	public void modifyClientTooltip(List<Component> tooltip, ItemStack stack, PacketContext context) {
		tooltip.add(Component.literal(switch (weapon) {
			case SHOOTER -> "Shoots paint in your team's colour";
			case SPRAYER -> "Sprays a cone of droplets up close";
			case CHARGER -> "Hold to charge, release for a long line of paint";
			case SLOSHER -> "Throws a bucketful in a wide fan";
		}).withStyle(ChatFormatting.GRAY));
	}
}
