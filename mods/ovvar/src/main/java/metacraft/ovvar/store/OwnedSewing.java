package metacraft.ovvar.store;

import metacraft.ovvar.OvvarConfig;
import metacraft.ovvar.content.Looks;
import metacraft.ovvar.content.ModContent;
import metacraft.ovvar.content.OvveItem;
import metacraft.ovvar.content.Patches;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.Spot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Sewing and unpicking as the store sees them. An unowned ovve is sewn on the spot, as it always
 * was. An owned one is a view of its owner's design, so the change goes to the store first and the
 * ovve follows: the patch in hand was already taken when the click landed and is refunded if the
 * store says no, and an unpicked patch is only handed out once the store has let go of it. That
 * ordering is what makes two ovves of one owner not a patch duplicator.
 */
public final class OwnedSewing {
	private OwnedSewing() {}

	/**
	 * @param onSewn   the change is on the ovve (and in the store, or queued for it)
	 * @param onRefused nothing changed; why, for the player
	 */
	public static void sew(ItemStack ovve, Placement placement, Runnable onSewn, Consumer<String> onRefused) {
		DesignKey key = OvveItem.designKey(ovve);
		if (key == null) {
			if (Looks.sew(ovve, placement)) onSewn.run();
			else onRefused.accept("Cannot sew a patch on top of another patch!");
			return;
		}
		if (!Designs.loaded(key.owner())) {
			Designs.fetch(key.owner());
			onRefused.accept("The ovve's design is still loading, try again in a moment");
			return;
		}
		if (!Designs.current(key).canSew(placement)) {
			onRefused.accept("Cannot sew a patch on top of another patch!");
			return;
		}
		Designs.update(key, d -> d.sew(placement), outcome -> {
			switch (outcome) {
				case OK -> {
					OvveItem.refresh(ovve);
					onSewn.run();
				}
				case CONFLICT -> onRefused.accept("The design changed on another server, try again");
				case NOT_LOADED -> onRefused.accept("The ovve's design is still loading, try again in a moment");
				case UNREACHABLE -> {
					if (OvvarConfig.get().designs().sewWhenUnreachable()) {
						Designs.queue(key, d -> d.sew(placement), "sew " + placement.key());
						OvveItem.refresh(ovve);
						onSewn.run();
					} else {
						onRefused.accept("The design store cannot be reached, try again later");
					}
				}
			}
		});
	}

	/**
	 * @param onUnpicked the patch is off the ovve (and out of the store, or queued): hand it over
	 * @param onRefused  nothing changed; why, for the player
	 */
	public static void unpick(ItemStack ovve, Spot spot, Consumer<Placement> onUnpicked, Consumer<String> onRefused) {
		DesignKey key = OvveItem.designKey(ovve);
		if (key == null) {
			Placement there = Looks.at(ovve, spot);
			Patches.Patch patch = Looks.unpick(ovve, spot);
			if (patch != null) onUnpicked.accept(there);
			else onRefused.accept("Nothing to unpick there");
			return;
		}
		if (!Designs.loaded(key.owner())) {
			Designs.fetch(key.owner());
			onRefused.accept("The ovve's design is still loading, try again in a moment");
			return;
		}
		// What is there according to the store's copy, never the ovve's own (it may be stale).
		Optional<Placement> there = Designs.current(key).at(spot);
		if (there.isEmpty()) {
			onRefused.accept("Nothing to unpick there");
			return;
		}
		Placement placement = there.get();
		Designs.update(key, d -> d.unpick(spot), outcome -> {
			switch (outcome) {
				case OK -> {
					OvveItem.refresh(ovve);
					onUnpicked.accept(placement);
				}
				case CONFLICT -> onRefused.accept("The design changed on another server, try again");
				case NOT_LOADED -> onRefused.accept("The ovve's design is still loading, try again in a moment");
				case UNREACHABLE -> {
					if (OvvarConfig.get().designs().unpickWhenUnreachable()) {
						Designs.queue(key, d -> d.unpick(spot), "unpick " + placement.key());
						OvveItem.refresh(ovve);
						onUnpicked.accept(placement);
					} else {
						onRefused.accept("The design store cannot be reached, try again later");
					}
				}
			}
		});
	}

	/** One patch item into the player's inventory, or dropped at their feet. */
	public static void give(ServerPlayer player, Patches.Patch patch) {
		ItemStack stack = new ItemStack(ModContent.patchItem(patch));
		if (!player.getInventory().add(stack)) player.drop(stack, false, Prediction.SERVER_ONLY);
	}
}
