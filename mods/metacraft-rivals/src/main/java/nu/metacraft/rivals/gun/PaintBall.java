package nu.metacraft.rivals.gun;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.paint.Painter;

/**
 * The thrown blob. A snowball on the server (physics, hit detection, the break particles on impact)
 * that clients see as a snowball carrying a firework star tinted with the paint colour, so it is a
 * round coloured ball with no texture of its own. Paints on whatever it hits; never hurts anything.
 */
public final class PaintBall extends Snowball implements PolymerEntity {
	public static final EntityType<PaintBall> TYPE = EntityType.Builder.<PaintBall>of(PaintBall::new, MobCategory.MISC)
			.sized(0.25f, 0.25f)
			.clientTrackingRange(4)
			.updateInterval(10)
			.noSummon()
			.noSave()
			.build(ResourceKey.create(Registries.ENTITY_TYPE, Rivals.id("paint_ball")));

	private PaintColor color = PaintColor.MAGENTA;

	public PaintBall(EntityType<? extends Snowball> type, Level level) {
		super(type, level);
	}

	public PaintBall(ServerLevel level, LivingEntity shooter, PaintColor color) {
		super(TYPE, level);
		this.color = color;
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

	/** What clients see flying: a firework star whose explosion colour is the paint colour. */
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

	@Override
	protected void onHitBlock(BlockHitResult hit) {
		super.onHitBlock(hit);
		if (level() instanceof ServerLevel serverLevel) {
			Painter.splat(serverLevel, hit.getBlockPos(), hit.getDirection(), color, random);
		}
	}

	/** No damage (the snowball would hurt blazes); paint the ground under whoever was hit. */
	@Override
	protected void onHitEntity(EntityHitResult hit) {
		if (level() instanceof ServerLevel serverLevel) {
			BlockPos below = hit.getEntity().blockPosition().below();
			Painter.splat(serverLevel, below, Direction.UP, color, random);
		}
	}
}
