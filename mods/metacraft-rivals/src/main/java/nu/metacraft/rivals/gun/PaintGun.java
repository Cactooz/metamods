package nu.metacraft.rivals.gun;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The paint gun. Right-click throws a {@link PaintBall} in the colour of the holder's vanilla team;
 * no team, no shot. Vanilla clients keep sending use packets while the button is held, so the item
 * cooldown is the fire rate. Clients see a warped fungus on a stick wearing our 3D model; the model's
 * tank is dye-tinted, and each inventory tick writes the holder's team colour into the server-side
 * stack as that dye, so every viewer sees the gun in its holder's colour.
 */
public final class PaintGun extends Item implements PolymerItem {
	public static final Identifier ID = Rivals.id("paint_gun");
	public static final int COOLDOWN_TICKS = 4;
	public static final float VELOCITY = 1.8f;
	public static final float INACCURACY = 2.0f;
	public static PaintGun ITEM;

	public PaintGun(Properties properties) {
		super(properties);
	}

	public static void register() {
		ITEM = Registry.register(BuiltInRegistries.ITEM, ID,
				new PaintGun(new Item.Properties().stacksTo(1).setId(ResourceKey.create(Registries.ITEM, ID))));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
		Optional<PaintColor> color = PaintColor.byTeam(player.getTeam());
		if (color.isEmpty()) {
			actionBar(player, Component.literal("Join a team first: /team join " + PaintColor.values()[0].id)
					.withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		ItemStack gun = player.getItemInHand(hand);
		long now = serverLevel.getServer().getTickCount();
		Ink.finishIfDue(gun, now);
		if (Ink.isRefilling(gun, now)) return InteractionResult.FAIL;
		if (isSquid(player)) {
			actionBar(player, Component.literal("Can't shoot in squid form").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		if (Ink.get(gun) <= 0) {
			Ink.startRefill(gun, now);
			serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.8f, 0.9f);
			player.getCooldowns().addCooldown(gun, Ink.REFILL_TICKS);
			if (player instanceof ServerPlayer serverPlayer) InkHud.show(serverPlayer);
			return InteractionResult.FAIL;
		}
		shoot(serverLevel, player, color.get());
		feel(serverLevel, player, color.get());
		Ink.add(gun, -1);
		player.getCooldowns().addCooldown(gun, COOLDOWN_TICKS);
		if (player instanceof ServerPlayer serverPlayer) InkHud.show(serverPlayer);
		return InteractionResult.SUCCESS;
	}

	static void actionBar(Player player, Component text) {
		if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) serverPlayer.sendSystemMessage(text, true);
	}

	/** Task 8 turns this into the squid-form check; until then nobody is a squid. */
	public static boolean isSquid(Player player) {
		return false;
	}

	/** Throw one paint ball from the shooter's eyes along their view. */
	public static PaintBall shoot(ServerLevel level, LivingEntity shooter, PaintColor color) {
		PaintBall ball = new PaintBall(level, shooter, color);
		ball.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot(), 0.0f, VELOCITY, INACCURACY);
		level.addFreshEntity(ball);
		return ball;
	}

	/** The chunk of a shot: camera kick, a nudge back, a muzzle burst in the team colour, two layered sounds. */
	static void feel(ServerLevel level, Player shooter, PaintColor color) {
		Recoil.kick(shooter);
		Vec3 look = shooter.getLookAngle();
		shooter.push(-look.x * 0.06, 0, -look.z * 0.06);
		shooter.hurtMarked = true;
		Vec3 muzzle = shooter.getEyePosition().add(look.scale(0.9));
		level.sendParticles(new DustParticleOptions(color.rgb, 1.2f), muzzle.x, muzzle.y, muzzle.z, 10, 0.1, 0.1, 0.1, 0.02);
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.7f, 0.7f);
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.SLIME_BLOCK_PLACE, SoundSource.PLAYERS, 0.5f, 1.4f);
	}

	/**
	 * The tank rule for a server-side gun stack, in one place: dyed with the team's paint colour (the
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
		if (!(entity instanceof LivingEntity holder)) return; // a dropped gun keeps the dye it had
		PlayerTeam team = holder.getTeam();
		DyedItemColor wanted = PaintColor.byTeam(team).map(color -> new DyedItemColor(color.rgb)).orElse(null);
		if (Objects.equals(stack.get(DataComponents.DYED_COLOR), wanted)) return;
		withTankColor(stack, team);
	}

	@Override
	public Item getPolymerItem(ItemStack stack, PacketContext context) {
		return Items.WARPED_FUNGUS_ON_A_STICK;
	}

	@Override
	public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
		return ID;
	}

	@Override
	public void modifyClientTooltip(List<Component> tooltip, ItemStack stack, PacketContext context) {
		tooltip.add(Component.literal("Shoots paint in your team's colour").withStyle(ChatFormatting.GRAY));
	}
}
