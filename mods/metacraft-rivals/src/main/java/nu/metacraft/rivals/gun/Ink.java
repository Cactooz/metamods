package nu.metacraft.rivals.gun;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The gun's ink tank, kept in the stack's custom data so it survives Polymer's client mapping and
 * item moves. Absent means full. A refill is a deadline in server ticks; it completes on the first
 * tick at or past it.
 *
 * <p>The deadline is an <em>absolute</em> server tick, and the tick count starts again at 0 every
 * boot, so a gun saved mid-refill comes back with a deadline far in the future and would otherwise
 * never fire again. A deadline more than {@link #REFILL_TICKS} ahead of now cannot have been set
 * this session: it is stale, and counts as due rather than as a refill in progress.
 */
public final class Ink {
	public static final int MAX = 40;
	public static final int REFILL_TICKS = 30;
	static final String INK = "rivals_ink";
	static final String REFILL_UNTIL = "rivals_refill_until";

	private Ink() {}

	public static int get(ItemStack stack) {
		return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getIntOr(INK, MAX);
	}

	public static void set(ItemStack stack, int ink) {
		int clamped = Math.max(0, Math.min(MAX, ink));
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(INK, clamped));
	}

	public static void add(ItemStack stack, int amount) {
		set(stack, get(stack) + amount);
	}

	public static boolean isRefilling(ItemStack stack, long now) {
		long until = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getLongOr(REFILL_UNTIL, -1L);
		return until >= 0 && now < until && !stale(until, now);
	}

	public static void startRefill(ItemStack stack, long now) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong(REFILL_UNTIL, now + REFILL_TICKS));
	}

	/** A deadline that could not have been set this session: left over from before a restart. */
	private static boolean stale(long until, long now) {
		return until - now > REFILL_TICKS;
	}

	/** Complete a refill whose deadline has passed (or is stale): full tank, deadline cleared. */
	public static void finishIfDue(ItemStack stack, long now) {
		long until = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getLongOr(REFILL_UNTIL, -1L);
		if (until < 0) return;
		if (now < until && !stale(until, now)) return;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			tag.putInt(INK, MAX);
			tag.remove(REFILL_UNTIL);
		});
	}
}
