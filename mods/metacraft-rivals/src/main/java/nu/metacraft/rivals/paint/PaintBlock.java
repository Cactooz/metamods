package nu.metacraft.rivals.paint;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;

/**
 * Paint of one colour. A vanilla multiface block on the server (six independent face flags, no
 * collision, faces drop off when their support goes), shown to clients as the colour's donor block
 * with the same faces. One block per cell, so one colour per cell.
 *
 * <p>Paint blocks only ever attach to full faces — {@link Painter} sends every other shape to
 * {@link PaintDisplays} quads — so vanilla's own survival rule is exactly the rule paint wants.
 */
public final class PaintBlock extends MultifaceBlock implements PolymerBlock {
	public final PaintColor color;

	public PaintBlock(Properties properties, PaintColor color) {
		super(properties);
		this.color = color;
		verifyDonor();
	}

	/** Fail startup, not gameplay, if a donor ever stops being a multiface block. */
	private void verifyDonor() {
		BlockState donor = color.donor.defaultBlockState();
		for (Direction d : DIRECTIONS) {
			if (!donor.hasProperty(getFaceProperty(d))) {
				throw new IllegalStateException("[" + Rivals.MOD_ID + "] donor " + color.donor + " for paint colour "
						+ color.id + " has no " + d + " face property; it cannot show paint");
			}
		}
		if (!donor.hasProperty(WATERLOGGED)) {
			throw new IllegalStateException("[" + Rivals.MOD_ID + "] donor " + color.donor + " for paint colour "
					+ color.id + " has no waterlogged property");
		}
	}

	@Override
	public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
		BlockState out = color.donor.defaultBlockState().setValue(WATERLOGGED, false);
		for (Direction d : DIRECTIONS) {
			BooleanProperty face = getFaceProperty(d);
			out = out.setValue(face, state.getValue(face));
		}
		return out;
	}
}
