package nu.metacraft.rivals;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Attribute modifiers and bookkeeping for squid form and enemy ink, applied and removed exactly once
 * per player.
 *
 * <p>Squid form is four transient modifiers rather than potion effects: half size (so a squid fits
 * where a player does not), much faster, a slightly higher hop, and a taller step so it glides over
 * kerbs and slabs instead of stalling on them. Enemy ink takes the jump away entirely, which is what
 * makes it a trap rather than an inconvenience. The modifiers are transient, so they are never
 * written to the player's save data; the set of squids only exists to keep {@link #enter} and
 * {@link #exit} idempotent and to answer {@link #isSquid} without reading attributes back.
 */
public final class SquidState {
	public static final Identifier SCALE_ID = Rivals.id("squid/scale");
	public static final Identifier SPEED_ID = Rivals.id("squid/speed");
	public static final Identifier JUMP_ID = Rivals.id("squid/jump");
	public static final Identifier STEP_ID = Rivals.id("squid/step");
	public static final Identifier NO_JUMP_ID = Rivals.id("ink/no_jump");

	private static final Set<UUID> SQUIDS = new HashSet<>();

	private SquidState() {}

	public static boolean isSquid(Player player) {
		return SQUIDS.contains(player.getUUID());
	}

	/** Become a squid. Does nothing if the player already is one. */
	public static void enter(Player player) {
		if (!SQUIDS.add(player.getUUID())) return;
		modifier(player, Attributes.SCALE, SCALE_ID, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
		modifier(player, Attributes.MOVEMENT_SPEED, SPEED_ID, 0.8, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
		modifier(player, Attributes.JUMP_STRENGTH, JUMP_ID, 0.2, AttributeModifier.Operation.ADD_VALUE);
		modifier(player, Attributes.STEP_HEIGHT, STEP_ID, 0.5, AttributeModifier.Operation.ADD_VALUE);
	}

	/** Back to a player. Does nothing if the player is not a squid. */
	public static void exit(Player player) {
		if (!SQUIDS.remove(player.getUUID())) return;
		remove(player, Attributes.SCALE, SCALE_ID);
		remove(player, Attributes.MOVEMENT_SPEED, SPEED_ID);
		remove(player, Attributes.JUMP_STRENGTH, JUMP_ID);
		remove(player, Attributes.STEP_HEIGHT, STEP_ID);
	}

	/** Standing in someone else's ink: no jumping out of it. */
	public static void applyEnemyInk(Player player) {
		modifier(player, Attributes.JUMP_STRENGTH, NO_JUMP_ID, -1.0, AttributeModifier.Operation.ADD_VALUE);
	}

	public static void clearEnemyInk(Player player) {
		remove(player, Attributes.JUMP_STRENGTH, NO_JUMP_ID);
	}

	/**
	 * Server stop: forget everyone. The set is keyed by UUID and would otherwise outlive the server it
	 * was filled from — a single-process restart (a dev run, an integrated server) would start with
	 * everyone still marked a squid. The modifiers themselves are transient and die with the entities.
	 */
	public static void clearAll() {
		SQUIDS.clear();
	}

	private static void modifier(Player player, Holder<Attribute> attribute, Identifier id, double amount, AttributeModifier.Operation op) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance != null && !instance.hasModifier(id)) instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, op));
	}

	private static void remove(Player player, Holder<Attribute> attribute, Identifier id) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance != null) instance.removeModifier(id);
	}
}
