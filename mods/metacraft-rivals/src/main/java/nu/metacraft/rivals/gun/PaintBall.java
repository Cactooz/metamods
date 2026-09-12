package nu.metacraft.rivals.gun;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.paint.Painter;
import org.jspecify.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The thrown blob. A snowball on the server (physics, hit detection) that no client ever sees:
 * {@link #sendPacketsTo} is false and a Polymer item display — a rounded, dyed blob model — rides
 * along on an {@link EntityAttachment}, squashing and stretching as it flies. Paints whatever it
 * hits; never hurts anything.
 *
 * <p>Two knobs on top of v2's straight flight. {@code bounces} lets a shot reflect off the face it
 * struck at {@link #BOUNCE_RESTITUTION} of its speed and keep going (the shooter's balls bounce
 * {@link Weapon#SHOOTER_BOUNCES} times, pancaking against each face and throwing off droplets);
 * {@code lifetime} makes a ball that has not hit anything splash the ground under itself and
 * vanish, which is what turns the same entity into a short-range sprayer droplet — and into the
 * spray a bounce throws off.
 */
public final class PaintBall extends Snowball implements PolymerEntity {
	public static final EntityType<PaintBall> TYPE = EntityType.Builder.<PaintBall>of(PaintBall::new, MobCategory.MISC)
			.sized(0.25f, 0.25f)
			.clientTrackingRange(4)
			.updateInterval(10)
			.noSummon()
			.noSave()
			.build(ResourceKey.create(Registries.ENTITY_TYPE, Rivals.id("paint_ball")));

	/** How much speed a bounce keeps. */
	public static final double BOUNCE_RESTITUTION = 0.62;
	/** A lobbed arc, heavier than a vanilla snowball's 0.03. */
	public static final double GRAVITY = 0.05;
	/** How far down {@link #expire} looks for a floor to splash. */
	private static final double EXPIRE_RAY = 4.0;
	/** The blob's resting size. */
	private static final float BLOB_SCALE = 0.55f;
	/** How far the stretch goes per block per tick of speed, and the most of it a blob can wear. */
	private static final float STRETCH_PER_SPEED = 0.9f;
	private static final float STRETCH_MAX = 0.6f;
	/** The impact pancake: this much wider across the face, this much flatter along its normal. */
	private static final float IMPACT_WIDE = 1.5f;
	private static final float IMPACT_FLAT = 0.45f;
	/** Ticks the pancake is held, then ticks it eases back into the flight shape over. */
	private static final int IMPACT_HOLD = 2;
	private static final int IMPACT_BLEND = 3;
	/** Interpolation on the display: a tick in flight, two for the softer impact squash. */
	private static final int FLIGHT_INTERPOLATION = 1;
	private static final int IMPACT_INTERPOLATION = 2;
	/** Below this the velocity has no direction worth orienting to, so the blob keeps its last one. */
	private static final double ORIENT_EPSILON = 1.0e-3;
	/** Lifted off the struck face so the bounced ball does not start inside it. */
	private static final double BOUNCE_LIFT = 0.05;
	/** What a bounce throws off: how many droplets, how long each lives, and how they leave. */
	private static final int BOUNCE_DROPLETS = 2;
	private static final int DROPLET_LIFETIME = 8;
	private static final double DROPLET_SPEED = 0.5;
	private static final double DROPLET_SCATTER = 0.15;

	private PaintColor color = PaintColor.MAGENTA;
	private int bounces = 1;
	private int lifetime = 0;
	private int splatRadius = Painter.RADIUS;
	private double gravity = GRAVITY;
	private int age = 0;
	private boolean droplet = false;
	/** Ticks left of the impact squash, and the face normal it is squashed against. */
	private int impact = 0;
	private Vec3 impactNormal = new Vec3(0, 1, 0);
	private @Nullable ElementHolder blob;
	private @Nullable ItemDisplayElement blobElement;

	public PaintBall(EntityType<? extends Snowball> type, Level level) {
		super(type, level);
	}

	/** The default shot: the shooter's bounces, no lifetime. */
	public PaintBall(ServerLevel level, LivingEntity shooter, PaintColor color) {
		this(level, shooter, color, Weapon.SHOOTER_BOUNCES, 0);
	}

	/**
	 * @param shooter  who fired it, or null for a ball with no shooter (a bounce droplet whose owner has
	 *                 since gone); a null shooter leaves the position to the caller
	 * @param bounces  how many times a block hit reflects instead of ending the ball
	 * @param lifetime ticks before the ball splashes the floor under itself, or 0 for no limit
	 */
	public PaintBall(ServerLevel level, @Nullable LivingEntity shooter, PaintColor color, int bounces, int lifetime) {
		super(TYPE, level);
		this.color = color;
		this.bounces = bounces;
		this.lifetime = lifetime;
		if (shooter != null) {
			setPos(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
			setOwner(shooter);
		}
		setItem(blob(color));
	}

	public static void register() {
		Registry.register(BuiltInRegistries.ENTITY_TYPE, Rivals.id("paint_ball"), TYPE);
		PolymerEntityUtils.registerType(TYPE);
	}

	public PaintColor color() {
		return color;
	}

	/** How far the impact splat reaches on the struck face: 0 is a single face, 2 the slosher's 5x5. */
	public int splatRadius() {
		return splatRadius;
	}

	public void setSplatRadius(int radius) {
		this.splatRadius = radius;
	}

	/**
	 * Override the fall rate for this ball. Read back through {@link #getDefaultGravity} every tick, so it
	 * can be set at any point in the ball's life; the slosher sets it once, before the throw.
	 */
	public void setGravity(double gravity) {
		this.gravity = gravity;
	}

	/** Bounces this ball has left; 0 means the next block hit ends it. */
	public int bouncesLeft() {
		return bounces;
	}

	/**
	 * Whether this ball is spray thrown off someone else's bounce. Droplets never throw droplets of their
	 * own: two per bounce off a ball that bounces twice is four, but a droplet that spawned droplets would
	 * be a chain with no end to it.
	 */
	public boolean isDroplet() {
		return droplet;
	}

	public void setDroplet(boolean droplet) {
		this.droplet = droplet;
	}

	/** The holder carrying the blob display, or null before the first tick and after removal. */
	public @Nullable ElementHolder blobHolder() {
		return blob;
	}

	/**
	 * The item the entity carries. Clients never see it — {@link #sendPacketsTo} is false, so the entity
	 * and the item-break event vanilla would fire on impact never reach anyone — but the stack is still
	 * what the ball reads as anywhere it is inspected server-side, and it carries the colour.
	 */
	public static ItemStack blob(PaintColor color) {
		ItemStack stack = new ItemStack(Items.FIREWORK_STAR);
		stack.set(DataComponents.FIREWORK_EXPLOSION,
				new FireworkExplosion(FireworkExplosion.Shape.SMALL_BALL, IntList.of(color.rgb), IntList.of(), false, false));
		return stack;
	}

	@Override
	protected Item getDefaultItem() {
		return Items.FIREWORK_STAR;
	}

	@Override
	public EntityType<?> getPolymerEntityType(PacketContext context) {
		return EntityTypes.SNOWBALL;
	}

	/** The entity itself is never sent: the blob display is the whole of what players see. */
	@Override
	public boolean sendPacketsTo(ServerPlayer player) {
		return false;
	}

	@Override
	protected double getDefaultGravity() {
		return gravity;
	}

	@Override
	public void tick() {
		super.tick();
		if (isRemoved() || !(level() instanceof ServerLevel serverLevel)) return;
		if (blob == null) attachBlob();
		shape();
		age++;
		if (lifetime > 0 && age >= lifetime) expire(serverLevel);
	}

	/** The display that players actually see: a dyed blob model glued to this entity, gliding a tick behind. */
	private void attachBlob() {
		ItemStack stack = new ItemStack(Items.STICK);
		stack.set(DataComponents.ITEM_MODEL, Rivals.id("blob"));
		stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color.rgb));
		ElementHolder holder = new ElementHolder();
		ItemDisplayElement element = new ItemDisplayElement(stack);
		element.setItemDisplayContext(ItemDisplayContext.FIXED);
		element.setInterpolationDuration(FLIGHT_INTERPOLATION);
		element.setTeleportDuration(1);
		element.setScale(new Vector3f(BLOB_SCALE, BLOB_SCALE, BLOB_SCALE));
		holder.addElement(element);
		EntityAttachment.ofTicking(holder, this);
		blob = holder;
		blobElement = element;
	}

	/**
	 * Squash and stretch, the thing that separates a blob of paint from a pebble. In flight the model's
	 * local +Y is turned to point along the velocity and the blob is drawn out along it by the speed —
	 * what it gains in length it loses across, so the volume reads constant. On a bounce it pancakes
	 * against the face it struck for {@link #IMPACT_HOLD} ticks and then eases back into the flight
	 * shape over {@link #IMPACT_BLEND} more; a ball that simply kept its flying shape through a bounce
	 * read as bouncing off nothing.
	 *
	 * <p>The old vertical sine wobble is gone: it was a shape unrelated to what the ball was doing, and
	 * at the tick rate a display interpolates over it mostly read as jitter.
	 */
	private void shape() {
		if (blobElement == null) return;
		Vec3 v = getDeltaMovement();
		float stretch = (float) Math.min(STRETCH_MAX, STRETCH_PER_SPEED * v.length());
		Vector3f flight = new Vector3f(BLOB_SCALE / (1 + stretch), BLOB_SCALE * (1 + stretch), BLOB_SCALE / (1 + stretch));
		if (impact > 0) {
			boolean held = impact > IMPACT_BLEND;
			float eased = held ? 0.0f : (IMPACT_BLEND - impact) / (float) IMPACT_BLEND;
			Vector3f pancake = new Vector3f(BLOB_SCALE * IMPACT_WIDE, BLOB_SCALE * IMPACT_FLAT, BLOB_SCALE * IMPACT_WIDE);
			blobElement.setScale(pancake.lerp(flight, eased));
			blobElement.setLeftRotation(orient(impactNormal));
			blobElement.setInterpolationDuration(held ? IMPACT_INTERPOLATION : FLIGHT_INTERPOLATION);
			impact--;
		} else {
			blobElement.setScale(flight);
			blobElement.setLeftRotation(orient(v));
			blobElement.setInterpolationDuration(FLIGHT_INTERPOLATION);
		}
		blobElement.startInterpolationIfDirty();
	}

	/** The rotation that turns the model's local up onto {@code direction}; identity if there is none. */
	private static Quaternionf orient(Vec3 direction) {
		double length = direction.length();
		if (length < ORIENT_EPSILON) return new Quaternionf();
		return new Quaternionf().rotationTo(0.0f, 1.0f, 0.0f,
				(float) (direction.x / length), (float) (direction.y / length), (float) (direction.z / length));
	}

	/** Out of time: splash the first surface within {@link #EXPIRE_RAY} straight down, then go. */
	private void expire(ServerLevel level) {
		Vec3 from = position();
		Vec3 to = from.subtract(0, EXPIRE_RAY, 0);
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		if (hit.getType() == HitResult.Type.BLOCK) {
			Painter.splash(level, hit.getLocation(), hit.getBlockPos(), hit.getDirection(), color, random, splatRadius, this);
		}
		discard();
	}

	/**
	 * {@link Snowball#onHit} discards the ball once the hit has been handled, so a bounce cannot go
	 * through the usual {@link #onHitBlock} path: it has to answer the hit here and return before super
	 * ever runs. Everything else — entity hits, a world-border hit (bouncing off the border would leave
	 * the ball skimming a wall that is not there), and the block hit that spends the last bounce — falls
	 * through to v2's behaviour (super dispatches to {@code onHitBlock}/{@code onHitEntity}, then discards).
	 */
	@Override
	protected void onHit(HitResult result) {
		if (bounces > 0 && result instanceof BlockHitResult hit && !hit.isWorldBorderHit()
				&& level() instanceof ServerLevel serverLevel) {
			super.onHitBlock(hit); // the vanilla block-hit effects still belong to a bounce
			Painter.splash(serverLevel, hit.getLocation(), hit.getBlockPos(), hit.getDirection(), color, random, splatRadius, this);
			bounces--;
			Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
			Vec3 v = getDeltaMovement();
			Vec3 reflected = v.subtract(normal.scale(2 * v.dot(normal))).scale(BOUNCE_RESTITUTION);
			setDeltaMovement(reflected);
			setPos(hit.getLocation().add(normal.scale(BOUNCE_LIFT)));
			impact = IMPACT_HOLD + IMPACT_BLEND;
			impactNormal = normal;
			spatter(serverLevel, hit.getLocation(), reflected);
			return;
		}
		super.onHit(result);
	}

	/**
	 * What a bounce throws off: a couple of droplets that leave along the reflection, at half its speed
	 * and scattered a little, each a short-lived single-face ball of its own. Paint that keeps going
	 * after the blob has left is most of what makes the bounce read as liquid rather than as rubber. The
	 * droplets carry the flag, so nothing they hit throws more.
	 */
	private void spatter(ServerLevel level, Vec3 at, Vec3 reflected) {
		level.playSound(null, at.x, at.y, at.z, SoundEvents.SLIME_BLOCK_STEP, SoundSource.BLOCKS, 0.5f, 1.6f);
		if (droplet) return;
		LivingEntity shooter = getOwner() instanceof LivingEntity living ? living : null;
		for (int i = 0; i < BOUNCE_DROPLETS; i++) {
			PaintBall drop = new PaintBall(level, shooter, color, 0, DROPLET_LIFETIME);
			drop.setDroplet(true);
			drop.setSplatRadius(0);
			drop.setPos(at.x, at.y, at.z);
			Vec3 scatter = new Vec3(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1);
			if (scatter.lengthSqr() > 1.0e-6) scatter = scatter.normalize().scale(DROPLET_SCATTER);
			drop.setDeltaMovement(reflected.scale(DROPLET_SPEED).add(scatter));
			level.addFreshEntity(drop);
		}
	}

	@Override
	protected void onHitBlock(BlockHitResult hit) {
		super.onHitBlock(hit);
		if (level() instanceof ServerLevel serverLevel) {
			Painter.splash(serverLevel, hit.getLocation(), hit.getBlockPos(), hit.getDirection(), color, random, splatRadius, this);
		}
	}

	/** No damage (the snowball would hurt blazes); splash from where the ball is, over the ground under the target. */
	@Override
	protected void onHitEntity(EntityHitResult hit) {
		if (level() instanceof ServerLevel serverLevel) {
			BlockPos below = hit.getEntity().blockPosition().below();
			Painter.splash(serverLevel, position(), below, Direction.UP, color, random, splatRadius, this);
		}
	}

	/**
	 * The blob is not an entity of its own: nothing else would ever take it down. Hooked on
	 * {@code onRemoval} rather than {@code remove}, because {@link #setRemoved} is final and calls
	 * {@code onRemoval} directly — a chunk unload takes that path and never goes through {@code remove}.
	 */
	@Override
	public void onRemoval(RemovalReason reason) {
		super.onRemoval(reason);
		if (blob != null) {
			blob.destroy();
			blob = null;
			blobElement = null;
		}
	}
}
