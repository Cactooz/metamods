package nu.metacraft.rivals.gun;

import com.mojang.authlib.GameProfile;
import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
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
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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
import nu.metacraft.rivals.gun.WeaponTuning.Param;
import nu.metacraft.rivals.paint.Painter;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Every paint weapon, in one item class parameterised by a {@link Weapon}. Right click fires: it throws
 * paint in the colour of the holder's vanilla team, and no team means no shot. Left click is the
 * special — a splat bomb on three of the four weapons, and the charger's own shot, since the charger's
 * right click is the scope and pressing both buttons at once is how a scoped rifle is fired. Vanilla clients keep sending use packets
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
				player.drop(stack, false, Prediction.SERVER_ONLY);
			}
		}
		return given;
	}

	/**
	 * The tick each player's splat bomb is ready again, by UUID. The bomb is one special rather than one
	 * per weapon, so switching guns does not hand out a second one, and it is deliberately not the item
	 * cooldown: that is the fire rate, and a special that stopped the trigger for four seconds would be
	 * a punishment rather than a choice. Absolute server ticks, so the map is cleared when the server
	 * stops — a deadline further ahead than the cooldown itself cannot have been set this session and is
	 * treated as spent, the same rule {@link Ink} uses for a stale refill.
	 */
	private static final Map<UUID, Long> SPECIAL_READY = new HashMap<>();
	/** The tick each player's left click was last answered, so one click is one special. */
	private static final Map<UUID, Long> LAST_LEFT_CLICK = new HashMap<>();

	/**
	 * Left click, from every path it can arrive on. A left click on a block or an entity reaches the
	 * server as an attack packet and then a swing packet in the same tick, and a click at thin air as
	 * the swing alone; the two callbacks here answer the first pair and the {@code handlePunch} mixin the
	 * swing, all of them through {@link #leftClick}, which takes the first of the tick and ignores the
	 * rest. Both callbacks refuse the vanilla action: a paint weapon must not break the arena, and a
	 * special thrown by punching someone must not also be a punch.
	 */
	public static void init() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
				holdsWeapon(player) ? answerLeftClick(player) : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				holdsWeapon(player) ? answerLeftClick(player) : InteractionResult.PASS);
		// Both maps are absolute server ticks, and the tick count starts again at 0 every boot.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			SPECIAL_READY.clear();
			LAST_LEFT_CLICK.clear();
		});
	}

	/**
	 * Forget a player. Both maps are absolute server ticks keyed by UUID; a player who leaves would
	 * otherwise keep their entries until the server stopped. Called from {@link
	 * nu.metacraft.rivals.PlayerTick}'s disconnect hook, alongside the squid bookkeeping.
	 */
	public static void forget(Player player) {
		SPECIAL_READY.remove(player.getUUID());
		LAST_LEFT_CLICK.remove(player.getUUID());
	}

	private static boolean holdsWeapon(Player player) {
		return player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof PaintWeapon;
	}

	private static InteractionResult answerLeftClick(Player player) {
		leftClick(player);
		return InteractionResult.FAIL;
	}

	/**
	 * The special, once per tick per player, for whoever is holding a paint weapon in their main hand.
	 * Returns whether anything happened, which is what the tests read.
	 */
	public static boolean leftClick(Player player) {
		if (!(player.level() instanceof ServerLevel level)) return false;
		ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
		if (!(held.getItem() instanceof PaintWeapon weapon)) return false;
		long now = level.getServer().getTickCount();
		Long last = LAST_LEFT_CLICK.put(player.getUUID(), now);
		if (last != null && last == now) return false;
		return weapon.special(level, player, held);
	}

	/**
	 * What a left click does with this weapon in hand.
	 *
	 * <p>Three of the four throw a splat bomb: a slow lob that splashes a wide patch of paint where it
	 * lands and hurts everyone from another team standing in it, for most of a tank and a four-second
	 * wait of its own. The charger fires instead — the shot it has been charging under the scope if the
	 * player is scoped, and a snap shot at no charge if they are not — because the one weapon whose right
	 * click is already a hold needs its own button to pull the trigger with.
	 */
	public boolean special(ServerLevel level, Player player, ItemStack gun) {
		Optional<PaintColor> ready = ready(level, player, gun); // team, refill, squid
		if (ready.isEmpty()) return false;
		PaintColor color = ready.get();
		WeaponTuning tuning = WeaponTuning.get(weapon);
		if (weapon == Weapon.CHARGER) {
			float charge = chargeOf(player);
			// The ink is checked before the scope is let go: a shot refused for an empty tank leaves the
			// player still aiming, rather than dropping them out of the scope for nothing.
			if (Ink.get(gun) < chargeCost(tuning, Math.max(charge, 0.0f))) {
				outOfInk(level, player, gun);
				return false;
			}
			if (charge >= 0) player.stopUsingItem();
			return chargerShot(level, player, gun, Math.max(charge, 0.0f), color);
		}
		long now = level.getServer().getTickCount();
		int wait = tuning.intValue(Param.SPECIAL_COOLDOWN);
		Long readyAt = SPECIAL_READY.get(player.getUUID());
		if (readyAt != null && now < readyAt && readyAt - now <= wait) {
			actionBar(player, Component.literal("Splat bomb in " + ((readyAt - now + 19) / 20) + "s")
					.withStyle(ChatFormatting.GRAY));
			return false;
		}
		int cost = tuning.intValue(Param.SPECIAL_INK);
		if (Ink.get(gun) < cost) {
			outOfInk(level, player, gun);
			return false;
		}
		splatBomb(level, player, color, tuning);
		Ink.add(gun, -cost);
		SPECIAL_READY.put(player.getUUID(), now + wait);
		if (player instanceof ServerPlayer serverPlayer) InkHud.show(serverPlayer);
		return true;
	}

	/** Ticks until this player's splat bomb is ready, or 0 when it is. What the tests and the hint read. */
	public static long specialWait(Player player, long now) {
		Long readyAt = SPECIAL_READY.get(player.getUUID());
		if (readyAt == null || now >= readyAt) return 0;
		return readyAt - now;
	}

	/**
	 * Lob the bomb: a big slow blob thrown above the crosshair, with the blast and the wide splat radius
	 * on it, and no bounce — it is meant to land where it was aimed and go off there.
	 */
	private void splatBomb(ServerLevel level, Player player, PaintColor color, WeaponTuning tuning) {
		PaintBall bomb = new PaintBall(level, player, color, 0, tuning.intValue(Param.SPECIAL_LIFETIME));
		bomb.setWeapon(weapon);
		bomb.setSplatRadius(tuning.intValue(Param.SPECIAL_RADIUS));
		bomb.setDamage(tuning.floatValue(Param.SPECIAL_DAMAGE));
		bomb.setGravity(tuning.value(Param.SPECIAL_GRAVITY));
		bomb.setBlast(Weapon.SPECIAL_BLAST);
		bomb.setBlobScale(Weapon.SPECIAL_SCALE);
		bomb.shootFromRotation(player, player.getXRot() + Weapon.SPECIAL_PITCH, player.getYRot(), 0.0f,
				tuning.floatValue(Param.SPECIAL_VELOCITY), 0.0f);
		level.addFreshEntity(bomb);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW,
				SoundSource.PLAYERS, 0.9f, 0.5f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SLIME_BLOCK_PLACE,
				SoundSource.PLAYERS, 0.8f, 0.6f);
	}

	/**
	 * How charged the scoped charger in this player's hands is, from 0 to 1, or −1 when they are not
	 * scoped with one at all. Read-only — the scope is let go by whoever fires — and shared with
	 * {@link InkHud}, which is where the number is actually shown to the player.
	 */
	public static float chargeOf(Player player) {
		ItemStack using = player.getUseItem();
		if (!player.isUsingItem() || !(using.getItem() instanceof PaintWeapon gun) || gun.weapon != Weapon.CHARGER) {
			return -1.0f;
		}
		int held = gun.getUseDuration(using, player) - player.getUseItemRemainingTicks();
		// A full charge in no ticks at all would divide by zero; one tick is the shortest charge there is.
		return Math.min(1.0f, held / (float) Math.max(1, WeaponTuning.get(Weapon.CHARGER).intValue(Param.CHARGE_FULL)));
	}

	/** What a charger shot at this charge costs: {@code charge_ink_min} to {@code charge_ink_full}. */
	private static int chargeCost(WeaponTuning tuning, float charge) {
		double inkMin = tuning.value(Param.CHARGE_INK_MIN);
		return (int) Math.round(inkMin + (tuning.value(Param.CHARGE_INK_FULL) - inkMin) * charge);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
		ItemStack gun = player.getItemInHand(hand);
		Optional<PaintColor> ready = ready(serverLevel, player, gun); // team, refill, squid
		if (ready.isEmpty()) return InteractionResult.FAIL;
		PaintColor color = ready.get();
		WeaponTuning tuning = WeaponTuning.get(weapon);
		// A tank that cannot cover the shot is as good as empty: one ink must not buy a fifteen-ink slosh.
		if (Ink.get(gun) < tuning.intValue(Param.INK)) {
			outOfInk(serverLevel, player, gun);
			return InteractionResult.FAIL;
		}
		// The charger spends nothing on the press: right click is the scope, and its shot, ink and cooldown
		// all wait for the left click that fires it.
		if (weapon == Weapon.CHARGER) {
			player.startUsingItem(hand);
			return InteractionResult.CONSUME;
		}
		fire(serverLevel, player, color);
		feel(serverLevel, player, color);
		Ink.add(gun, -tuning.intValue(Param.INK));
		player.getCooldowns().addCooldown(gun, tuning.intValue(Param.COOLDOWN));
		if (player instanceof ServerPlayer serverPlayer) InkHud.show(serverPlayer);
		return weapon == Weapon.SLOSHER ? InteractionResult.SUCCESS_SERVER : InteractionResult.CONSUME;
	}

	/**
	 * Throw this weapon's paint from the shooter's eyes along their view. Three of the four weapons are
	 * the same shot with different numbers — {@code count} balls, spread {@code fan_yaw} degrees apart
	 * around the view and pitched by {@code fan_pitch}, each carrying the weapon's gravity, bounces,
	 * lifetime, splat radius and damage — so there is one loop rather than an arm apiece: a shooter's
	 * single flat ball is that fan with one ball in it, a sprayer's cone is three of them at nine
	 * degrees of inaccuracy, a slosher's four at ten degrees of deliberate yaw. Every number is read
	 * from {@link WeaponTuning} here, at the shot, so {@code /rivals tune} lands on the next click.
	 */
	public void fire(ServerLevel level, Player player, PaintColor color) {
		if (weapon == Weapon.CHARGER) return; // the charger throws no ball; see chargerShot
		WeaponTuning tuning = WeaponTuning.get(weapon);
		int count = tuning.intValue(Param.COUNT);
		float fanYaw = tuning.floatValue(Param.FAN_YAW);
		float pitch = player.getXRot() + tuning.floatValue(Param.FAN_PITCH);
		for (int i = 0; i < count; i++) {
			// Centred on the view: an odd count puts one ball down the crosshair, an even one straddles it.
			float offset = (i - (count - 1) / 2.0f) * fanYaw;
			PaintBall ball = new PaintBall(level, player, color,
					tuning.intValue(Param.BOUNCES), tuning.intValue(Param.LIFETIME));
			ball.setWeapon(weapon);
			ball.setSplatRadius(tuning.intValue(Param.SPLAT_RADIUS));
			ball.setDamage(tuning.floatValue(Param.DAMAGE));
			ball.setGravity(tuning.value(Param.GRAVITY));
			ball.shootFromRotation(player, pitch, player.getYRot() + offset, 0.0f,
					tuning.floatValue(Param.VELOCITY), tuning.floatValue(Param.SPREAD));
			level.addFreshEntity(ball);
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

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		// Letting go of the scope is not a shot: the trigger is the left click, so that the aim and the
		// firing are two buttons rather than one gesture. A short scoped hold is the one case worth a
		// word, because someone clicking this weapon the way the other three are clicked sees nothing
		// happen at all and reads it as broken.
		if (weapon != Weapon.CHARGER || !(entity instanceof Player player)) return false;
		int held = getUseDuration(stack, entity) - timeLeft;
		if (held < WeaponTuning.get(weapon).intValue(Param.CHARGE_MIN)) {
			actionBar(player, Component.literal("Hold right click to aim, left click to fire").withStyle(ChatFormatting.GRAY));
		}
		return false;
	}

	/**
	 * The charger's shot, at {@code charge} from 0 (a snap shot) to 1 (a full one). The charge is the
	 * whole weapon: it scales range, ink, damage and kick together, each of them from its {@code *_min}
	 * at no charge to its {@code *_full} at a full one. The shot itself is hitscan: one clip along the
	 * view, a line of paint on the floor under it, and a splash where it stops — under the feet of
	 * whoever was standing in the way, if anyone was, and otherwise on the block face it ran into.
	 * Whoever stopped it also takes the charge's share of {@code charge_damage_min}..{@code
	 * charge_damage_full}, unless they are on the shooter's own team. Every one of those numbers is read
	 * off {@link WeaponTuning} here, at the shot, so {@code /rivals tune} lands on the next one.
	 */
	public boolean chargerShot(ServerLevel serverLevel, Player player, ItemStack stack, float charge) {
		if (weapon != Weapon.CHARGER) return false;
		Optional<PaintColor> ready = ready(serverLevel, player, stack);
		return ready.isPresent() && chargerShot(serverLevel, player, stack, charge, ready.get());
	}

	/** The shot itself, for a caller that has already asked {@link #ready} what colour it is firing. */
	private boolean chargerShot(ServerLevel serverLevel, Player player, ItemStack stack, float charge, PaintColor color) {
		WeaponTuning tuning = WeaponTuning.get(weapon);
		int cost = chargeCost(tuning, charge);
		if (Ink.get(stack) < cost) {
			outOfInk(serverLevel, player, stack);
			return false;
		}
		double rangeMin = tuning.value(Param.RANGE_MIN);
		double range = rangeMin + (tuning.value(Param.RANGE_FULL) - rangeMin) * charge;
		Vec3 from = player.getEyePosition();
		Vec3 reach = player.getLookAngle().scale(range);
		Vec3 to = from.add(reach);
		BlockHitResult hit = serverLevel.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		boolean struck = hit.getType() == HitResult.Type.BLOCK;
		Vec3 end = struck ? hit.getLocation() : to;
		// Anyone standing in the way stops the line where they are: scanned only as far as the block hit,
		// so a hit that comes back is by construction the nearer of the two. Paint goes under their feet,
		// the same shape a ball's entity hit makes — a charger line that passed through a player and
		// painted the wall behind them read as a miss.
		AABB along = player.getBoundingBox().expandTowards(reach).inflate(1.0);
		EntityHitResult inTheWay = ProjectileUtil.getEntityHitResult(serverLevel, player, from, end, along,
				// isPickable, so a dropped item, an XP orb or someone else's paint ball in flight does not
				// stop the line: those are not what a charger shot is aimed at.
				candidate -> candidate != player && candidate.isAlive() && candidate.isPickable() && !candidate.isSpectator(), 0.0f);
		if (inTheWay != null) end = inTheWay.getLocation();
		int painted = Painter.line(serverLevel, from, end, color, player);
		if (inTheWay != null) {
			BlockPos below = inTheWay.getEntity().blockPosition().below();
			painted += Painter.splash(serverLevel, end, below, Direction.UP, color, serverLevel.getRandom(), player);
			// The one weapon whose damage rides the charge: a full-charge line is the hardest hit in the
			// game, a barely-held one is a poke. Attributed to the player, so a kill goes on their name.
			if (PaintBall.hostile(color, inTheWay.getEntity())) {
				float damageMin = tuning.floatValue(Param.CHARGE_DAMAGE_MIN);
				float hurt = damageMin + (tuning.floatValue(Param.CHARGE_DAMAGE_FULL) - damageMin) * charge;
				if (inTheWay.getEntity().hurtServer(serverLevel, serverLevel.damageSources().indirectMagic(player, player), hurt)) {
					InkOnScreen.hit(inTheWay.getEntity(), color, hurt);
				}
			}
		} else if (struck) {
			painted += Painter.splash(serverLevel, end, hit.getBlockPos(), hit.getDirection(), color, serverLevel.getRandom(), player);
		}
		Rivals.LOGGER.debug("charger: charge {}, range {}, {} cells painted", charge, range, painted);
		Ink.add(stack, -cost);
		player.getCooldowns().addCooldown(stack, tuning.intValue(Param.COOLDOWN));
		// A half charge should not buck like a full one, so the kick rides the charge.
		Recoil.kick(player, tuning.floatValue(Param.KICK) * charge);
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
		Recoil.kick(shooter, WeaponTuning.get(weapon).floatValue(Param.KICK));
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
		shooter.syncVelocity = true;
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
		// The ink-on-screen meter rides the weapon's data LED, and this is the one place it is written, so a
		// weapon stowed with a full screen cannot come back out still carrying a live number. The holder is
		// noted with it: only their own client is ever told the value.
		if (holder instanceof Player carrier) {
			InkOnScreen.put(stack, InkOnScreen.ledFor(carrier));
			InkOnScreen.owner(stack, carrier.getUUID());
		}
		PlayerTeam team = holder.getTeam();
		DyedItemColor wanted = PaintColor.byTeam(team).map(color -> new DyedItemColor(color.rgb)).orElse(null);
		if (Objects.equals(stack.get(DataComponents.DYED_COLOR), wanted)) return;
		withTankColor(stack, team);
	}

	/**
	 * The LED value this stack may show to {@code viewer}: the real one for the player whose meter it is,
	 * {@link InkOnScreen#IDLE} for everybody else. A lit LED on someone else's gun would be a tell — and
	 * worse, the ink post effect hunts the whole lower half of the frame for that signature, so another
	 * player's third-person weapon walking past would splatter the finder's own screen.
	 */
	public static int ledForViewer(ItemStack stack, @Nullable UUID viewer) {
		UUID owner = InkOnScreen.ownerOf(stack);
		return viewer != null && viewer.equals(owner) ? InkOnScreen.ledOf(stack) : InkOnScreen.IDLE;
	}

	/**
	 * The client's copy of a weapon: Polymer's own (the mapped item, the dye, the model) with the data LED
	 * masked to whoever is being sent it. {@link PacketContext#GAME_PROFILE} is the receiving player.
	 */
	@Override
	public ItemStack getPolymerItemStack(ItemStack stack, TooltipFlag flag, PacketContext context, HolderLookup.Provider lookup) {
		ItemStack out = PolymerItem.super.getPolymerItemStack(stack, flag, context, lookup);
		GameProfile viewer = context.get(PacketContext.GAME_PROFILE);
		InkOnScreen.put(out, ledForViewer(stack, viewer == null ? null : viewer.id()));
		return out;
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
			case CHARGER -> "Right click to aim, left click to fire a long line of paint";
			case SLOSHER -> "Throws a bucketful in a wide fan";
		}).withStyle(ChatFormatting.GRAY));
	}
}
