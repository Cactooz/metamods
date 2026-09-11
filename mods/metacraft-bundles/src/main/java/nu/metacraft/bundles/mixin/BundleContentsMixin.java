package nu.metacraft.bundles.mixin;

import com.google.common.base.Suppliers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.DataResult;
import nu.metacraft.bundles.METAcraftBundles;
import nu.metacraft.bundles.extensions.BundlesComponentExtensions;
import nu.metacraft.bundles.util.BundleHelper;
import org.apache.commons.lang3.math.Fraction;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.GrowableMutableContainer;

/**
 * A bundle's size factor: a bundle whose factor is F holds F times a vanilla bundle's worth.
 * The stored weight stays the bundle's fullness in [0, 1] — that is what the client's bar
 * reads — so every item's weight is divided by F on the way in. 26.3 keeps the weight
 * arithmetic in {@code Mutable}'s private helpers, reached from its instance methods; the
 * hooks wrap those calls where {@code this} (and so the factor) is at hand.
 */
@Mixin(BundleContents.class)
public abstract class BundleContentsMixin implements BundlesComponentExtensions.Internal {

	@Unique
	private Fraction bundleSizeFactor = Fraction.ONE;

	/** An inner bundle weighs its fullness times its own factor: a half-full ×2 bundle is a whole vanilla bundle's worth. */
	@WrapOperation(
			method = "getWeight",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/item/component/BundleContents;weight()Lcom/mojang/serialization/DataResult;"
			)
	)
	private static DataResult<Fraction> innerBundleWeight(BundleContents inner, Operation<DataResult<Fraction>> original) {
		return original.call(inner).flatMap(
				fraction -> BundleHelper.runSafeFractionOperation(() -> fraction.multiplyBy(BundleHelper.getStoredBundleSizeFactor(inner)))
		);
	}

	@Override
	public void metacraft_bundles$setBundleSizeFactor(Fraction factor) {
		this.bundleSizeFactor = factor;
	}

	@Override
	public Fraction metacraft_bundles$getBundleSizeFactor() {
		return bundleSizeFactor;
	}

	@ModifyReturnValue(method = "equals", at = @At("RETURN"))
	private boolean equalsIncludingFactor(boolean original, @Local(argsOnly = true) Object other) {
		return original && other instanceof BundleContents contents && this.bundleSizeFactor.equals(BundleHelper.getStoredBundleSizeFactor(contents));
	}

	/** The mutable is made here now (its constructor is private): it starts with this bundle's factor. */
	@ModifyReturnValue(
			method = "asMutable()Lnet/minecraft/world/item/component/BundleContents$Mutable;",
			at = @At("RETURN")
	)
	private BundleContents.Mutable carryFactor(BundleContents.Mutable mutable) {
		((BundlesComponentExtensions.Internal) (Object) mutable).metacraft_bundles$setBundleSizeFactor(this.bundleSizeFactor);
		return mutable;
	}

	@Mixin(BundleContents.Mutable.class)
	public static abstract class Mutable extends GrowableMutableContainer<BundleContents> implements Internal {
		@Shadow private Fraction weight;

		@Unique
		private Fraction bundleSizeFactor = Fraction.ONE;

		protected Mutable(List<ItemStack> items) {
			super(items);
		}

		/** An item weighs a factor-th of its vanilla weight here. */
		@Unique
		private DataResult<Fraction> scaled(DataResult<Fraction> vanilla) {
			return vanilla.flatMap(fraction -> BundleHelper.runSafeFractionOperation(() -> fraction.divideBy(bundleSizeFactor)));
		}

		@WrapOperation(
				method = {
						"tryInsert(Lnet/minecraft/world/item/ItemStack;)I",
						"tryTransfer(Lnet/minecraft/world/inventory/Slot;Lnet/minecraft/world/entity/player/Player;)I"
				},
				at = @At(
						value = "INVOKE",
						target = "Lnet/minecraft/world/item/component/BundleContents;getWeight(Lnet/minecraft/world/item/ItemInstance;)Lcom/mojang/serialization/DataResult;"
				)
		)
		private DataResult<Fraction> itemWeightOnInsert(net.minecraft.world.item.ItemInstance item, Operation<DataResult<Fraction>> original) {
			return scaled(original.call(item));
		}

		@WrapOperation(
				method = {"removeOne", "setItem"},
				at = @At(
						value = "INVOKE",
						target = "Lnet/minecraft/world/item/component/BundleContents$Mutable;getStackedWeight(Lnet/minecraft/world/item/ItemStack;)Lorg/apache/commons/lang3/math/Fraction;"
				)
		)
		private Fraction stackWeightOnRemove(ItemStack stack, Operation<Fraction> original) {
			return original.call(stack).divideBy(bundleSizeFactor);
		}

		/**
		 * {@code getWeightWithAddedItems(w, s)} answers {@code w + s} when that is at most one, else
		 * null; it is static, so the factor cannot reach inside. Feed it {@code F·w − (F − 1)}: it then
		 * accepts exactly when {@code w + s/F ≤ 1}, and its answer maps back as {@code (r + F − 1) / F}.
		 */
		@WrapOperation(
				method = {"setItem", "addSlotWithItem"},
				at = @At(
						value = "INVOKE",
						target = "Lnet/minecraft/world/item/component/BundleContents$Mutable;getWeightWithAddedItems(Lorg/apache/commons/lang3/math/Fraction;Lnet/minecraft/world/item/ItemStack;)Lorg/apache/commons/lang3/math/Fraction;"
				)
		)
		private @Nullable Fraction capacityOnSet(Fraction current, ItemStack stack, Operation<Fraction> original) {
			try {
				Fraction slack = bundleSizeFactor.subtract(Fraction.ONE);
				Fraction shifted = current.multiplyBy(bundleSizeFactor).subtract(slack);
				Fraction answer = original.call(shifted, stack);
				return answer == null ? null : answer.add(slack).divideBy(bundleSizeFactor);
			} catch (ArithmeticException overflow) {
				return null;
			}
		}

		@ModifyReturnValue(method = "toImmutable", at = @At("RETURN"))
		private BundleContents build(BundleContents original) {
			((BundlesComponentExtensions.Internal) (Object) original).metacraft_bundles$setBundleSizeFactor(bundleSizeFactor);
			((BundleContentsAccessor) (Object) original).setWeight(
					Suppliers.memoize(
							() -> BundleContentsAccessor.callComputeContentWeight(items).flatMap(
									fraction -> BundleHelper.runSafeFractionOperation(() -> fraction.divideBy(bundleSizeFactor))
							)
					)
			);
			return original;
		}

		@Override
		public void metacraft_bundles$setBundleSizeFactor(Fraction factor) {
			bundleSizeFactor = factor;
			weight = BundleContentsAccessor.callComputeContentWeight(this.items).flatMap(
					fraction -> BundleHelper.runSafeFractionOperation(() -> fraction.divideBy(factor))
			).resultOrPartial(METAcraftBundles.LOGGER::error).orElse(
					Fraction.ZERO
			);
		}

		@Override
		public Fraction metacraft_bundles$getBundleSizeFactor() {
			return bundleSizeFactor;
		}

		/** Never merge into a stack that is already at its max size; the list item is the first argument. */
		@WrapOperation(
				method = "findStackIndexWithinRange",
				at = @At(
						value = "INVOKE",
						target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"
				)
		)
		private boolean notIntoAFullStack(ItemStack existing, ItemStack adding, Operation<Boolean> original) {
			return existing.getCount() < existing.getMaxStackSize() && original.call(existing, adding);
		}

		/**
		 * {@code toImmutable()} merges identical stacks it finds; two half stacks must not become one
		 * past the max size. (Reached only after slot edits set {@code needsFlattening}; the game tests
		 * cover the insert path, this guard the GUI path.)
		 */
		@WrapOperation(
				method = "mergeIdenticalStacks",
				at = @At(
						value = "INVOKE",
						target = "Lnet/minecraft/world/item/component/BundleContents$Mutable;findStackIndexWithinRange(Lnet/minecraft/world/item/ItemStack;II)I"
				)
		)
		private int noMergePastMaxSize(BundleContents.Mutable self, ItemStack stack, int from, int to, Operation<Integer> original) {
			int index = original.call(self, stack, from, to);
			return index >= 0 && items.get(index).getCount() + stack.getCount() > stack.getMaxStackSize() ? -1 : index;
		}

		/** A merge that would pass the max stack size is split: the full stack goes back in front, the rest is the merge. */
		@ModifyArg(
				method = "tryInsert(Lnet/minecraft/world/item/ItemStack;)I",
				at = @At(
						value = "INVOKE",
						target = "Lnet/minecraft/world/item/ItemStack;copyWithCount(I)Lnet/minecraft/world/item/ItemStack;"
				)
		)
		private int splitAtMaxSize(int total, @Local(name = "removedStack") ItemStack removedStack) {
			if (total > removedStack.getMaxStackSize()) {
				items.addFirst(removedStack.copyWithCount(removedStack.getMaxStackSize()));
				return total - removedStack.getMaxStackSize();
			}
			return total;
		}
	}
}
