package metacraft.ovvar.store;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.Spot;
import metacraft.ovvar.content.SpotPlacements;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One player's design for one chapter: the sewn patches, and the store's version of that row.
 * Visuals only — nothing about the item that carries it (pockets, enchantments, top up or down).
 * Version 0 is "not in the store yet"; every successful write is the previous version plus one,
 * and a write names the version it expects, so two servers cannot both remove the same patch.
 *
 * @param patches the sewn patches in sewing order, or null for none
 * @param version the store's version of this row, 0 when it has none
 */
public record Design(@Nullable SpotPlacements patches, long version) {
	public static final Design NONE = new Design(null, 0);

	public static final Codec<Design> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Placement.CODEC.listOf().optionalFieldOf("patches", List.of())
					.forGetter(d -> SpotPlacements.asPlacementList(d.placements())),
			Codec.LONG.optionalFieldOf("version", 0L).forGetter(Design::version)
	).apply(instance, (list, version) -> new Design(list.isEmpty() ? null : SpotPlacements.fromList(list).getOrThrow(), version)));

	public Optional<SpotPlacements> placements() {
		return Optional.ofNullable(patches);
	}

	public boolean isEmpty() {
		return patches == null;
	}

	/** Same patches, whatever the version. */
	public boolean samePatches(Optional<SpotPlacements> other) {
		return Objects.equals(placements(), other);
	}

	public Design withPatches(@Nullable SpotPlacements patches) {
		return new Design(patches, version);
	}

	public Design withVersion(long version) {
		return new Design(patches, version);
	}

	public boolean canSew(Placement placement) {
		return SpotPlacements.canApply(placements(), placement);
	}

	/** The design with this placement sewn (replacing whatever it overlaps). */
	public Design sew(Placement placement) {
		return withPatches(SpotPlacements.apply(placements(), placement));
	}

	/** The design with the patch on this spot unpicked; unchanged when there is none. */
	public Design unpick(Spot spot) {
		return withPatches(placements().flatMap(p -> p.remove(spot)).orElse(null));
	}

	public Optional<Placement> at(Spot spot) {
		return placements().flatMap(p -> p.getPlacement(spot));
	}
}
