package metacraft.ovvar.content;

import eu.pb4.polymer.core.api.item.PolymerItem;
import metacraft.ovvar.Ovvar;
import metacraft.ovvar.OvvarConfig;
import metacraft.ovvar.store.Design;
import metacraft.ovvar.store.DesignKey;
import metacraft.ovvar.store.Designs;
import metacraft.ovvar.store.OwnedSewing;
import org.jspecify.annotations.Nullable;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A chapter's ovve: one item, worn in the legs slot, with pockets. It is a bundle with
 * {@link Pockets#SIZE} times the room (through metacraft-bundles, which also shows the real fill
 * level to vanilla clients) that can also be filled while worn, by clicking items onto the legs slot.
 *
 * Right click behaves as a bundle (hold to empty it); sneak + right click rolls the top up or
 * down. Neither equips it — drag it into the slot or shift-click. While the top is up the mod
 * keeps a companion {@link OvveTopItem} in the chest slot so the sleeves render. Leather-grade
 * defence, no durability (it breaking would spill someone's pockets). The client is handed a
 * bundle with our equipment asset, chosen per stack.
 *
 * An ovve belongs to a player ({@link ModComponents#OWNER}, set when a player first holds it) and
 * its patches are that player's design in the store ({@link Designs}); the component on the item
 * is a copy kept in step every tick, so a second ovve of the same owner looks the same and never
 * holds a patch of its own.
 */
public final class OvveItem extends BundleItem implements PolymerItem {
	public final Chapter chapter;
	private final Identifier id;

	public OvveItem(Properties properties, Chapter chapter, Identifier id) {
		super(properties);
		this.chapter = chapter;
		this.id = id;
	}

	public static boolean topUp(ItemStack ovve) {
		return Boolean.TRUE.equals(ovve.get(ModComponents.TOP_UP));
	}

	public static void setTopUp(ItemStack ovve, boolean up) {
		if (up) ovve.set(ModComponents.TOP_UP, true);
		else ovve.remove(ModComponents.TOP_UP);
	}

	// ---- ownership

	public static @Nullable UUID owner(ItemStack ovve) {
		return ovve.get(ModComponents.OWNER);
	}

	public static void setOwner(ItemStack ovve, @Nullable UUID owner) {
		if (owner == null) ovve.remove(ModComponents.OWNER);
		else ovve.set(ModComponents.OWNER, owner);
	}

	/** Where this ovve's design is filed, or null for an unowned one. */
	public static @Nullable DesignKey designKey(ItemStack ovve) {
		UUID owner = owner(ovve);
		return owner == null || !(ovve.getItem() instanceof OvveItem item) ? null : new DesignKey(owner, item.chapter);
	}

	/**
	 * Copies the owner's cached design onto the ovve when it differs (the store is the truth; the
	 * item only draws). Nothing happens for an unowned ovve or one whose owner is not loaded.
	 */
	public static void refresh(ItemStack ovve) {
		DesignKey key = designKey(ovve);
		if (key == null || !Designs.loaded(key.owner())) return;
		Optional<Design> design = Designs.cached(key);
		if (design.isEmpty()) return;   // no design yet: the holder's tick adopts what is on the item, once
		if (!design.get().samePatches(Looks.sewn(ovve))) Looks.setSewn(ovve, design.get().patches());
	}

	/**
	 * The store side of a player's inventory tick: bind an unowned ovve to this player, adopt the
	 * patches on it as their first design if they have none, commit a smithing-table sew, and
	 * keep the copy on the item in step with the design.
	 */
	public static void syncDesign(ServerPlayer player, ItemStack stack) {
		if (owner(stack) == null) {
			if (!OvvarConfig.get().designs().bindOnPickup()) return;
			setOwner(stack, player.getUUID());
		}
		DesignKey key = designKey(stack);
		if (key == null) return;
		if (!Designs.loaded(key.owner())) {
			Designs.fetch(key.owner());
			return;
		}
		Placement pending = stack.get(ModComponents.PENDING_SEW);
		if (pending != null) {
			stack.remove(ModComponents.PENDING_SEW);
			OwnedSewing.sew(stack, pending, () -> {}, why -> {
				OwnedSewing.give(player, pending.patch());
				player.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.RED));
			});
			return;   // the outcome refreshes the item; until then its own copy stands
		}
		if (Designs.cached(key).isEmpty() && !key.owner().equals(player.getUUID())) return;   // someone else's, not yet designed: leave it
		if (Designs.cached(key).isEmpty()) {
			// A first ovve with patches already on it (given by command, sewn while unowned): they become the design.
			var sewn = Looks.sewn(stack);
			if (sewn.isEmpty() || Designs.pending(key)) return;
			Designs.update(key, d -> d.withPatches(sewn.get()), outcome -> {
				if (outcome != Designs.Outcome.OK) Ovvar.LOGGER.warn("[ovvar] designs: adopting {}'s first design: {}", key, outcome);
			});
			return;
		}
		refresh(stack);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!player.isShiftKeyDown()) {
			var bundleContents = stack.get(DataComponents.BUNDLE_CONTENTS);
			if (bundleContents == null || bundleContents.isEmpty()) {
				Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
				if (equippable != null && equippable.swappable()) {
					return equippable.swapWithEquipmentSlot(stack, player);
				}
			}
			return super.use(level, player, hand);
		}
		if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
		if (!chapter.rollable) {
			serverPlayer.sendOverlayMessage(Component.literal("A " + chapter.garmentWord() + " has nothing to roll down"));
			return InteractionResult.FAIL;
		}
		boolean up = !topUp(stack);
		setTopUp(stack, up);
		serverPlayer.sendOverlayMessage(Component.literal(up ? "Top rolled up" : "Top rolled down"));
		return InteractionResult.SUCCESS;
	}

	// ---- wearing

	/** Worn in the legs slot (player or armour stand): keep the companion top in step every tick. */
	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
		if (entity instanceof ServerPlayer player) {
			// Off a stand and into a player's hands: the armour draws the patches again, and if
			// this player's pack cannot show them all, this is the one reload a sewing session ends in.
			if (stack.has(ModComponents.ON_STAND)) stack.remove(ModComponents.ON_STAND);
			syncDesign(player, stack);
			Looks.claimIfNeeded(player, stack);
		} else {
			refresh(stack);   // on a stand or a mannequin: follow the owner's design (loaded on demand)
			DesignKey key = designKey(stack);
			if (key != null && !Designs.loaded(key.owner())) Designs.fetch(key.owner());
		}
		if (slot == EquipmentSlot.LEGS && entity instanceof LivingEntity wearer) {
			OvveTop.sync(wearer, stack);
			OvveFeet.sync(wearer, stack);
		}
	}

	@Override
	public void modifyClientTooltip(List<Component> tooltip, ItemStack stack, PacketContext context) {
		boolean up = topUp(stack);
		tooltip.add(Component.literal(up ? "Zipped up" : "Zipped down").withStyle(ChatFormatting.GRAY));
		if (chapter.rollable) {
			tooltip.add(Component.literal("Sneak + right-click: " + (up ? "zip down" : "zip up")).withStyle(ChatFormatting.DARK_GRAY));
		}
		tooltip.add(Component.literal("Right-click: empty the pockets").withStyle(ChatFormatting.DARK_GRAY));
		var sewn = Looks.sewn(stack);
		if (sewn.isEmpty()) {
			tooltip.add(Component.literal("No patches yet").withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(Component.literal("Patches:").withStyle(ChatFormatting.GRAY));
			for (Placement p : SpotPlacements.asPlacementList(sewn)) {
				tooltip.add(Component.literal("  " + p.patch().name() + " — " + p.spot().label()).withStyle(ChatFormatting.GRAY));
			}
		}
		tooltip.add(Component.literal("Sew: put it on an armour stand, aim a patch at the spot, right-click").withStyle(ChatFormatting.DARK_GRAY));
	}

	@Override
	public Item getPolymerItem(ItemStack stack, PacketContext context) {
		return Items.BUNDLE;
	}

	@Override
	public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
		return id;
	}

	@Override
	public ItemStack getPolymerItemStack(ItemStack stack, TooltipFlag flag, PacketContext context, HolderLookup.Provider lookup) {
		ItemStack out = PolymerItem.super.getPolymerItemStack(stack, flag, context, lookup);
		OvveTop.dress(out, stack.get(DataComponents.EQUIPPABLE), stack, chapter, Piece.BOTTOM, !topUp(stack), context, lookup);
		return out;
	}
}
