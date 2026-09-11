package nu.metacraft.rivals.paint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import nu.metacraft.rivals.PaintColor;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Which cells the painter has painted, per level, and how many faces of each colour they hold now.
 *
 * <p>No deltas: {@link #count} reads every tracked cell's current state and drops cells that no longer
 * hold paint (removed by a player, by {@code updateShape} when the support went, or by reset). Vanilla
 * only calls {@code onPlace} when the block changes, so a face added to a same-colour cell would be
 * invisible to hooks; walking a few thousand positions once a second is cheap and cannot drift.
 * In memory only: a restart forgets the cells until paint is shot again.
 */
public final class PaintTally {
	private static final Map<ResourceKey<Level>, PaintTally> TALLIES = new HashMap<>();
	private static final Direction[] DIRECTIONS = Direction.values();

	private final Set<BlockPos> cells = new HashSet<>();

	public static PaintTally of(ServerLevel level) {
		return TALLIES.computeIfAbsent(level.dimension(), key -> new PaintTally());
	}

	/** Forget every level's cells (server stop). */
	public static void clearAll() {
		TALLIES.clear();
	}

	public void track(BlockPos cell) {
		cells.add(cell.immutable());
	}

	public int cells() {
		return cells.size();
	}

	/** Faces per colour over the tracked cells, pruning cells that hold no paint any more. Every colour has an entry. */
	public Map<PaintColor, Integer> count(ServerLevel level) {
		Map<PaintColor, Integer> counts = new EnumMap<>(PaintColor.class);
		for (PaintColor color : PaintColor.values()) counts.put(color, 0);
		Iterator<BlockPos> it = cells.iterator();
		while (it.hasNext()) {
			BlockPos pos = it.next();
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof PaintBlock paint)) {
				it.remove();
				continue;
			}
			int faces = 0;
			for (Direction d : DIRECTIONS) {
				if (state.getValue(MultifaceBlock.getFaceProperty(d))) faces++;
			}
			counts.merge(paint.color, faces, Integer::sum);
		}
		return counts;
	}

	/** This colour's fraction of all painted faces; 0 when nothing is painted. */
	public static float share(Map<PaintColor, Integer> counts, PaintColor color) {
		int total = 0;
		for (int n : counts.values()) total += n;
		return total == 0 ? 0f : counts.getOrDefault(color, 0) / (float) total;
	}

	/** Remove every tracked paint block from the level and forget the cells. Returns how many were removed. */
	public int reset(ServerLevel level) {
		int removed = 0;
		for (BlockPos pos : cells) {
			if (level.getBlockState(pos).getBlock() instanceof PaintBlock) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				removed++;
			}
		}
		cells.clear();
		return removed;
	}
}
