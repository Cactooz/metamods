package nu.metacraft.rivals.gun;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
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
	public static final float VELOCITY = 1.5f;
	public static final float INACCURACY = 1.0f;
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
			// Server players without a live connection (fake or offline players) cannot be sent to.
			if (player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
				serverPlayer.sendSystemMessage(Component.literal("Join a team first: /team join magenta").withStyle(ChatFormatting.RED), true);
			}
			return InteractionResult.FAIL;
		}
		shoot(serverLevel, player, color.get());
		player.getCooldowns().addCooldown(player.getItemInHand(hand), COOLDOWN_TICKS);
		return InteractionResult.SUCCESS;
	}

	/** Throw one paint ball from the shooter's eyes along their view. */
	public static PaintBall shoot(ServerLevel level, LivingEntity shooter, PaintColor color) {
		PaintBall ball = new PaintBall(level, shooter, color);
		ball.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot(), 0.0f, VELOCITY, INACCURACY);
		level.addFreshEntity(ball);
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6f, 0.8f);
		return ball;
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
