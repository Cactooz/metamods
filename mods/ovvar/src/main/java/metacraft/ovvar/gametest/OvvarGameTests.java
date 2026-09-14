package metacraft.ovvar.gametest;

import metacraft.ovvar.OvvarConfig;
import metacraft.ovvar.content.Chapter;
import metacraft.ovvar.content.Looks;
import metacraft.ovvar.content.ModContent;
import metacraft.ovvar.content.Patches;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.Spot;
import metacraft.ovvar.content.SpotPlacements;
import metacraft.ovvar.sewing.Seam;
import metacraft.ovvar.sewing.SewingFont;
import metacraft.ovvar.sewing.SewingGame;
import metacraft.ovvar.sewing.StandAim;
import metacraft.ovvar.sewing.StandDisplays;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Rotations;
import net.minecraft.gametest.framework.GameTestHelper;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * {@code ./gradlew runGametest}. The aiming tests hold the sewing aim to the forward mapping:
 * for every cell, on stands in several poses and facings, a ray straight at the cell's centre
 * must resolve to that cell — near faces from outside, far faces (sneaking) from the other
 * side of the part. The stitching test plays the minigame's clicks the way the dialog sends them.
 */
public final class OvvarGameTests {
	private static final Rotations REST = new Rotations(0, 0, 0);
	private static final Rotations ARMS_OUT_R = new Rotations(0, 0, 60), ARMS_OUT_L = new Rotations(0, 0, -60);
	private static final Rotations LEGS_APART_R = new Rotations(0, 0, 25), LEGS_APART_L = new Rotations(0, 0, -25);

	@GameTest
	public void aimEveryCellAtRest(GameTestHelper helper) {
		aimEveryCell(helper, 0, REST, REST, REST, REST, spot -> true);
	}

	@GameTest
	public void aimEveryCellPosedAndTurned(GameTestHelper helper) {
		aimEveryCell(helper, 137, ARMS_OUT_R, ARMS_OUT_L, LEGS_APART_R, LEGS_APART_L, spot -> true);
	}

	@GameTest
	public void aimFarFacesWhenSneaking(GameTestHelper helper) {
		ArmorStand stand = stand(helper, 210, REST, REST, REST, REST);
		List<String> wrong = new ArrayList<>();
		for (Spot spot : Spot.values()) {
			if (spot == Spot.SEAT) continue;
			// Look at the cell from just inside its part: the ray exits through this face, which
			// sneaking picks (the far face of the part it is in).
			StandAim.CellPoint cell = StandAim.cell(stand, spot);
			Vec3 eye = cell.centre().subtract(cell.normal().scale(0.15));
			StandAim.Hit hit = StandAim.aim(eye, cell.normal(), stand, true, 6);
			if (hit == null || hit.spot() != spot) wrong.add(spot.id() + " → " + (hit == null ? "miss" : hit.spot()));
		}
		if (!wrong.isEmpty()) helper.fail("sneak-aim off for " + wrong.size() + " cell(s): " + wrong);
		helper.succeed();
	}

	@GameTest
	public void stitchingSewsOnTheLastPull(GameTestHelper helper) {
		ArmorStand stand = stand(helper, 0, REST, REST, REST, REST);
		ItemStack ovve = new ItemStack(ModContent.ovve(Chapter.values()[0]));
		stand.setItemSlot(EquipmentSlot.LEGS, ovve);
		ServerPlayer player = sewer(helper, stand);
		Patches.Patch itk = Patches.get("itk");
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.patchItem(itk), 3));
		Placement placement = new Placement(Spot.FRONT_TOP_LEFT, itk);

		SewingGame.start(player, stand, placement, itk);
		CompoundTag stale = SewingGame.nextPull(player);
		if (stale == null) helper.fail("no seam open after start");
		// The dialog must encode the way the packet sends it (a bad button or body would fail here).
		JsonElement json = Dialog.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, SewingGame.dialog(player))
				.getOrThrow(message -> new IllegalStateException("dialog does not encode: " + message));
		if (!json.toString().contains("ovvar:pull")) helper.fail("no pull button in " + json);
		// A wrong stitch number (an old dialog, a double click) must not count.
		CompoundTag wrong = stale.copy();
		wrong.putInt("stitch", 5);
		SewingGame.click(player, SewingGame.PULL, Optional.of(wrong));
		if (SewingGame.nextPull(player).getIntOr("stitch", -1) != 0) helper.fail("a stale pull advanced the seam");
		// Pull until sewn; every pull but the last leaves the ovve untouched.
		int pulls = 0;
		while (Looks.at(stand.getItemBySlot(EquipmentSlot.LEGS), Spot.FRONT_TOP_LEFT) == null) {
			CompoundTag next = SewingGame.nextPull(player);
			if (next == null) helper.fail("seam closed after " + pulls + " pull(s) without sewing");
			if (next.getIntOr("stitch", -1) != pulls) helper.fail("expected stitch " + pulls + ", dialog offers " + next);
			SewingGame.click(player, SewingGame.PULL, Optional.of(next));
			if (++pulls > 32) helper.fail("still not sewn after " + pulls + " pulls");
		}
		if (!placement.equals(Looks.at(stand.getItemBySlot(EquipmentSlot.LEGS), Spot.FRONT_TOP_LEFT))) helper.fail("sewn placement is wrong");
		if (SewingGame.nextPull(player) != null) helper.fail("seam still open after sewing");
		int left = player.getMainHandItem().getCount();
		if (!player.isCreative() && left != 2) helper.fail("expected one patch used, " + left + " left of 3");
		helper.succeed();
	}

	/**
	 * Every sprite label in every state of every seam measures exactly what the client centres
	 * without scrolling (the button's width minus its insets) and draws only within 1 px of its
	 * button: the rules {@link metacraft.ovvar.sewing.SewingFont} rests on. Also that each dialog encodes.
	 */
	@GameTest
	public void sewingLabelsFitTheirButtons(GameTestHelper helper) {
		int labels = 0;
		for (Chapter chapter : Chapter.values()) {
			for (Patches.Patch patch : Patches.all()) {
				Spot spot = patch.seat() ? Spot.SEAT : Spot.FRONT_TOP_LEFT;
				for (int stitches = OvvarConfig.MIN_STITCHES; stitches <= OvvarConfig.MAX_STITCHES; stitches++) {
					for (int done = 0; done < stitches; done++) {
						Dialog dialog = SewingGame.dialog(chapter, patch, spot, stitches, done);
						if (chapter == Chapter.values()[0] && done == 0) {
							Dialog.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, dialog)
									.getOrThrow(message -> new IllegalStateException("dialog does not encode: " + message));
						}
						MultiActionDialog multi = (MultiActionDialog) dialog;
						List<ActionButton> buttons = new ArrayList<>(multi.actions());
						multi.exitAction().ifPresent(buttons::add);
						int pulls = 0;
						for (ActionButton button : buttons) {
							String where = chapter.id + "/" + patch.id() + " " + done + "/" + stitches;
							int width = button.button().width();
							String text = button.button().label().getString();
							int measured = SewingFont.width(text);
							if (measured != width - 2 * SewingFont.LABEL_INSET) {
								helper.fail(where + ": a " + width + " px button's label measures " + measured);
							}
							// A cell's glyph covers its button and 1 px around; only the last cell reaches back over the picture.
							int[] extent = SewingFont.extent(text);
							if (extent[0] < SewingFont.overlayX(0) || extent[1] > width + 2 * SewingFont.OVERHANG) {
								helper.fail(where + ": a label draws from " + extent[0] + " to " + extent[1] + " on a " + width + " px button");
							}
							if (button.action().isPresent() && buttons.indexOf(button) < multi.actions().size()) pulls++;
							labels++;
						}
						if (pulls != 1) helper.fail(chapter.id + "/" + patch.id() + " " + done + "/" + stitches + ": " + pulls + " needle buttons");
					}
				}
			}
		}
		if (labels == 0) helper.fail("no labels checked");
		helper.succeed();
	}

	@GameTest
	public void cuttingTheThreadSewsNothing(GameTestHelper helper) {
		ArmorStand stand = stand(helper, 0, REST, REST, REST, REST);
		stand.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModContent.ovve(Chapter.values()[0])));
		ServerPlayer player = sewer(helper, stand);
		Patches.Patch itk = Patches.get("itk");
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.patchItem(itk), 3));
		SewingGame.start(player, stand, new Placement(Spot.BACK_TOP_RIGHT, itk), itk);
		SewingGame.click(player, SewingGame.PULL, Optional.of(SewingGame.nextPull(player)));
		SewingGame.click(player, SewingGame.CUT, Optional.empty());
		if (SewingGame.nextPull(player) != null) helper.fail("seam still open after cutting");
		if (!Looks.sewn(stand.getItemBySlot(EquipmentSlot.LEGS)).isEmpty()) helper.fail("something was sewn");
		if (player.getMainHandItem().getCount() != 3) helper.fail("a patch was used up");
		helper.succeed();
	}

	/**
	 * A player two blocks in front of the stand with a connection (an embedded channel), so it
	 * can be sent dialogs. Only the deprecated helper wires one up; the replacement has none.
	 */
	@SuppressWarnings("removal")
	private static ServerPlayer sewer(GameTestHelper helper, ArmorStand stand) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setGameMode(GameType.SURVIVAL);
		player.setPos(stand.getX(), stand.getY(), stand.getZ() + 2);
		return player;
	}

	private static void aimEveryCell(
		GameTestHelper helper, float yaw, Rotations rightArm, Rotations leftArm, Rotations rightLeg, Rotations leftLeg,
		Predicate<Spot> reachable
	) {
		ArmorStand stand = stand(helper, yaw, rightArm, leftArm, rightLeg, leftLeg);
		List<String> wrong = new ArrayList<>();
		for (Spot spot : Spot.values()) {
			if (spot == Spot.SEAT || !reachable.test(spot)) continue;
			StandAim.CellPoint cell = StandAim.cell(stand, spot);
			Vec3 eye = cell.centre().add(cell.normal().scale(0.3));   // close in: nothing else in the way
			StandAim.Hit hit = StandAim.aim(eye, cell.normal().scale(-1), stand, false, 6);
			if (hit == null || hit.spot() != spot) wrong.add(spot.id() + " → " + (hit == null ? "miss" : hit.spot()));
		}
		if (!wrong.isEmpty()) helper.fail("aim off for " + wrong.size() + " cell(s): " + wrong);
		helper.succeed();
	}

	/**
	 * The config's stitch count is for a cell-sized patch (a 32-texel outline); a longer outline
	 * gets proportionally more holes, within the dialog's range.
	 */
	@GameTest
	public void stitchesScaleWithTheOutline(GameTestHelper helper) {
		record Case(String patch, int base, int expected) {}
		List<Case> cases = List.of(
				new Case("itk", 6, 9),        // 12×12, 48 texels: 9 exactly
				new Case("nyckeln", 6, 6),    // 8×8, 30 texels: 5.625 rounds up
				new Case("rivals", 6, 9),     // the 16×8 seat patch, 48 texels: 9 exactly
				new Case("itk", 12, 16),      // 18 capped at the dialog's most
				new Case("nyckeln", 1, 1));   // never below one
		List<String> wrong = new ArrayList<>();
		for (Case c : cases) {
			int got = Seam.stitchesFor(Patches.get(c.patch), c.base);
			if (got != c.expected) wrong.add(c.patch + " at base " + c.base + ": expected " + c.expected + ", got " + got);
		}
		if (!wrong.isEmpty()) helper.fail("stitch counts off: " + wrong);
		// A game started on an oversize patch sews with the scaled count, and its dialog says so.
		ArmorStand stand = stand(helper, 0, REST, REST, REST, REST);
		stand.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModContent.ovve(Chapter.values()[0])));
		ServerPlayer player = sewer(helper, stand);
		Patches.Patch itk = Patches.get("itk");
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.patchItem(itk)));
		SewingGame.start(player, stand, new Placement(Spot.FRONT_TOP_LEFT, itk), itk);
		String json = Dialog.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, SewingGame.dialog(player))
				.getOrThrow(message -> new IllegalStateException("dialog does not encode: " + message)).toString();
		if (!json.contains("Stitch 1 of " + Seam.stitchesFor(itk, OvvarConfig.get().stitches()))) helper.fail("the ITK dialog does not offer the scaled count: " + json);
		helper.succeed();
	}

	/**
	 * A stand spawned in the air (as {@code /ovvar showcase} does when flying) falls and lands;
	 * once it rests, every patch sprite must lie on its cell — not where the cell was a tick
	 * before the stand stopped.
	 */
	@GameTest(maxTicks = 120)
	public void spritesFollowAStandThatFalls(GameTestHelper helper) {
		ArmorStand stand = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(2, 6, 2));
		ItemStack ovve = new ItemStack(ModContent.ovve(Chapter.values()[0]));
		Placement placement = new Placement(Spot.LEG_FRONT_TOP_R, Patches.get("itk"));
		Looks.setSewn(ovve, SpotPlacements.fromList(List.of(placement)).getOrThrow());
		stand.setItemSlot(EquipmentSlot.LEGS, ovve);
		helper.runAfterDelay(60, () -> {
			if (!stand.onGround()) helper.fail("stand still falling after 60 ticks, at y " + stand.getY());
			List<StandDisplays.Sprite> sprites = StandDisplays.sprites(stand);
			if (sprites.isEmpty()) helper.fail("no sprites on the stand");
			for (StandDisplays.Sprite sprite : sprites) {
				Vec3 cell = StandAim.cell(stand, sprite.placement().spot()).centre();
				double off = sprite.pos().distanceTo(cell);
				if (off > 0.03) helper.fail(sprite.placement().key() + " sprite is " + String.format("%.3f", off) + " blocks off its cell after landing");
			}
			helper.succeed();
		});
	}

	private static ArmorStand stand(GameTestHelper helper, float yaw, Rotations rightArm, Rotations leftArm, Rotations rightLeg, Rotations leftLeg) {
		ArmorStand stand = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(2, 1, 2));
		stand.setYRot(yaw);
		stand.setYBodyRot(yaw);
		stand.yBodyRotO = yaw;
		stand.setRightArmPose(rightArm);
		stand.setLeftArmPose(leftArm);
		stand.setRightLegPose(rightLeg);
		stand.setLeftLegPose(leftLeg);
		stand.setNoGravity(true);
		return stand;
	}
}
