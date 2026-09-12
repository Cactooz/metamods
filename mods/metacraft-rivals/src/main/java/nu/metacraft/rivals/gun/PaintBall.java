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
import net.minecraft.util.Mth;
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
import org.joml.Vector3f;

/**
 * The thrown blob. A snowball on the server (physics, hit detection) that no client ever sees:
 * {@link #sendPacketsTo} is false and a Polymer item display — a rounded, dyed blob model — rides
 * along on an {@link EntityAttachment}, squashing and stretching as it flies. Paints whatever it
 * hits; never hurts anything.
 *
 * <p>Two knobs on top of v2's straight flight. {@code bounces} lets a shot reflect off the face it
 * struck at {@link #BOUNCE_RESTITUTION} of its speed and keep going (the shooter's balls bounce
 * once); {@code lifetime} makes a ball that has not hit anything splash the ground under itself and
 * vanish, which is what turns the same entity into a short-range sprayer droplet.
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
	public static final double BOUNCE_RESTITUTION = 0.45;
	/** A lobbed arc, heavier than a vanilla snowball's 0.03. */
	public static final double GRAVITY = 0.05;
	/** How far down {@link #expire} looks for a floor to splash. */
	private static final double EXPIRE_RAY = 4.0;
	/** The blob's resting size, and how far the wobble pushes it either way. */
	private static final float BLOB_SCALE = 0.55f;
	private static final float BLOB_WOBBLE = 0.12f;
	/** Lifted off the struck face so the bounced ball does not start inside it. */
	private static final double BOUNCE_LIFT = 0.05;

	private PaintColor color = PaintColor.MAGENTA;
	private int bounces = 1;
	private int lifetime = 0;
	private int splatRadius = Painter.RADIUS;
	private double gravity = GRAVITY;
	private int age = 0;
	private @Nullable ElementHolder blob;
	private @Nullable ItemDisplayElement blobElement;

	public PaintBall(EntityType<? extends Snowball> type, Level level) {
		super(type, level);
	}

	/** The default shot: one bounce, no lifetime. */
	public PaintBall(ServerLevel level, LivingEntity shooter, PaintColor color) {
		this(level, shooter, color, 1, 0);
	}

	/**
	 * @param bounces  how many times a block hit reflects instead of ending the ball
	 * @param lifetime ticks before the ball splashes the floor under itself, or 0 for no limit
	 */
	public PaintBall(ServerLevel level, LivingEntity shooter, PaintColor color, int bounces, int lifetime) {
		super(TYPE, level);
		this.color = color;
		this.bounces = bounces;
		this.lifetime = lifetime;
		setPos(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
		setOwner(shooter);
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
	 * Override the fall rate for this ball. Read through {@link #getDefaultGravity}, so it must be set
	 * before the ball is added to the level — a heavier ball is a different arc, not a mid-flight change.
	 */
	public void setGravity(double gravity) {
		this.gravity = gravity;
	}

	/** Bounces this ball has left; 0 means the next block hit ends it. */
	public int bouncesLeft() {
		return bounces;
	}

	/** The holder carrying the blob display, or null before the first tick and after removal. */
	public @Nullable ElementHolder blobHolder() {
		return blob;
	}

	/**
	 * The item the entity carries. Clients never see the entity itself any more, but the stack still
	 * drives the vanilla impact particles and reads as a coloured ball anywhere the stack is inspected.
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
		wobble();
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
		element.setInterpolationDuration(1);
		element.setTeleportDuration(1);
		element.setScale(new Vector3f(BLOB_SCALE, BLOB_SCALE, BLOB_SCALE));
		holder.addElement(element);
		EntityAttachment.ofTicking(holder, this);
		blob = holder;
		blobElement = element;
	}

	/** Squash and stretch: what the blob gains across it, it loses in height. */
	private void wobble() {
		if (blobElement == null) return;
		float w = BLOB_WOBBLE * Mth.sin(age * 1.1f);
		blobElement.setScale(new Vector3f(BLOB_SCALE + w, BLOB_SCALE - w, BLOB_SCALE + w));
		blobElement.startInterpolationIfDirty();
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
	 * ever runs. Everything else — entity hits, and the block hit that spends the last bounce — falls
	 * through to v2's behaviour (super dispatches to {@code onHitBlock}/{@code onHitEntity}, then discards).
	 */
	@Override
	protected void onHit(HitResult result) {
		if (bounces > 0 && result instanceof BlockHitResult hit && level() instanceof ServerLevel serverLevel) {
			Painter.splash(serverLevel, hit.getLocation(), hit.getBlockPos(), hit.getDirection(), color, random, splatRadius, this);
			bounces--;
			Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
			Vec3 v = getDeltaMovement();
			setDeltaMovement(v.subtract(normal.scale(2 * v.dot(normal))).scale(BOUNCE_RESTITUTION));
			setPos(hit.getLocation().add(normal.scale(BOUNCE_LIFT)));
			return;
		}
		super.onHit(result);
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

	/** The blob is not an entity of its own: nothing else would ever take it down. */
	@Override
	public void remove(RemovalReason reason) {
		super.remove(reason);
		if (blob != null) {
			blob.destroy();
			blob = null;
			blobElement = null;
		}
	}
}
