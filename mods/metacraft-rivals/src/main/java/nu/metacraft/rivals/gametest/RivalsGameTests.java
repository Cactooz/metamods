package nu.metacraft.rivals.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.PlayerTick;
import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.SquidState;
import nu.metacraft.rivals.RivalsCommands;
import nu.metacraft.rivals.gun.PaintBall;
import nu.metacraft.rivals.gun.PaintWeapon;
import nu.metacraft.rivals.gun.Weapon;
import nu.metacraft.rivals.gun.Recoil;
import nu.metacraft.rivals.paint.PaintBlock;
import nu.metacraft.rivals.paint.PaintBlocks;
import nu.metacraft.rivals.paint.PaintDisplays;
import nu.metacraft.rivals.paint.Painter;
import nu.metacraft.rivals.paint.PaintTally;
import nu.metacraft.rivals.pack.SplatArt;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nu.metacraft.rivals.pack.RivalsPack;
import nu.metacraft.rivals.gun.Ink;
import nu.metacraft.rivals.gun.InkHud;

/**
 * Server-side game tests (Fabric GameTest API). Run headless with
 * {@code ./gradlew mods:metacraft-rivals:runGameTest}; each test gets an empty 8×8×8 structure and
 * positions passed to the helper are relative to it.
 */
public final class RivalsGameTests {
	@GameTest
	public void modLoads(GameTestHelper helper) {
		helper.succeed();
	}

	/** Every paint state is sent as its donor block with the same six face flags and never waterlogged. */
	@GameTest
	public void donorMappingKeepsFaces(GameTestHelper helper) {
		for (PaintColor color : PaintColor.values()) {
			PaintBlock block = PaintBlocks.of(color);
			BlockState state = block.defaultBlockState()
					.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
					.setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true);
			BlockState client = block.getPolymerBlockState(state, PacketContext.get());
			helper.assertTrue(client.is(color.donor), Component.literal(color.id + " maps to " + client));
			for (Direction d : Direction.values()) {
				boolean expected = d == Direction.DOWN || d == Direction.NORTH;
				helper.assertTrue(client.getValue(MultifaceBlock.getFaceProperty(d)) == expected,
						Component.literal(color.id + ": face " + d + " should be " + expected));
			}
			helper.assertTrue(!client.getValue(MultifaceBlock.WATERLOGGED), Component.literal(color.id + " sent waterlogged"));
		}
		helper.succeed();
	}

	/** Every generated splat is 32×32, paint texels carry the alpha marker 229, the rest is fully transparent. */
	@GameTest
	public void splatArtCarriesTheMarkerAlpha(GameTestHelper helper) throws IOException {
		for (PaintColor color : PaintColor.values()) {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(SplatArt.texture(color.rgb, SplatArt.SHAPES[0], 0)));
			helper.assertValueEqual(image.getWidth(), SplatArt.SIZE, color.id + " width");
			helper.assertValueEqual(image.getHeight(), SplatArt.SIZE, color.id + " height");
			int paint = 0;
			int clear = 0;
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int alpha = (image.getRGB(x, y) >>> 24) & 0xFF;
					if (alpha == SplatArt.PAINT_ALPHA) paint++;
					else if (alpha == 0) clear++;
					else helper.fail(color.id + ": unexpected alpha " + alpha + " at " + x + "," + y);
				}
			}
			helper.assertTrue(paint > 100 && clear > 50, color.id + ": paint=" + paint + " clear=" + clear);
		}
		helper.succeed();
	}

	/** The blockstate override lists 32 variants per face and every model/texture it names is in the pack file set. */
	@GameTest
	public void blockstateOverridesReferenceGeneratedModels(GameTestHelper helper) {
		Map<String, byte[]> files = SplatArt.packFiles();
		for (PaintColor color : PaintColor.values()) {
			String path = "assets/minecraft/blockstates/" + color.donorPath() + ".json";
			helper.assertTrue(files.containsKey(path), "override present: " + path);
			JsonObject state = JsonParser.parseString(new String(files.get(path), StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray multipart = state.getAsJsonArray("multipart");
			helper.assertValueEqual(multipart.size(), 7, color.id + " multipart entries (6 faces + none)");
			for (JsonElement part : multipart) {
				JsonArray apply = part.getAsJsonObject().getAsJsonArray("apply");
				helper.assertValueEqual(apply.size(), SplatArt.SHAPES.length * SplatArt.ROTATIONS, color.id + " variants per face");
				for (JsonElement variant : apply) {
					String model = variant.getAsJsonObject().get("model").getAsString(); // metacraft-rivals:block/splat_x_y_z
					String modelPath = "assets/metacraft-rivals/models/block/" + model.substring(model.indexOf('/') + 1) + ".json";
					helper.assertTrue(files.containsKey(modelPath), "model in pack: " + modelPath);
					JsonObject modelJson = JsonParser.parseString(new String(files.get(modelPath), StandardCharsets.UTF_8)).getAsJsonObject();
					String texture = modelJson.getAsJsonObject("textures").get("splat").getAsString();
					String texturePath = "assets/metacraft-rivals/textures/" + texture.substring(texture.indexOf(':') + 1) + ".png";
					helper.assertTrue(files.containsKey(texturePath), "texture in pack: " + texturePath);
				}
			}
		}
		helper.succeed();
	}

	/** Stone floor at relative y=1 over x,z in [0,size). */
	private static void stoneFloor(GameTestHelper helper, int size) {
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
	}

	private static int faces(BlockState state) {
		int n = 0;
		for (Direction d : Direction.values()) {
			if (state.getValue(MultifaceBlock.getFaceProperty(d))) n++;
		}
		return n;
	}

	/** A splat on the top of a floor block paints the cell above it, on its down face, in that colour. */
	@GameTest
	public void floorSplatPaintsCellAbove(GameTestHelper helper) {
		stoneFloor(helper, 5);
		BlockPos struck = new BlockPos(2, 1, 2);
		int painted = Painter.splat(helper.getLevel(), helper.absolutePos(struck), Direction.UP, PaintColor.MAGENTA,
				helper.getLevel().getRandom());
		helper.assertTrue(painted >= 5 && painted <= 9, "painted " + painted + " faces, expected 5..9");
		BlockState cell = helper.getBlockState(struck.above());
		helper.assertTrue(cell.is(PaintBlocks.of(PaintColor.MAGENTA)), Component.literal("cell above the hit is magenta paint, got " + cell));
		helper.assertTrue(cell.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)), "paint sits on its down face");
		helper.succeed();
	}

	/** The blob stays within one block of the hit in the plane, and never where the surface is missing. */
	@GameTest
	public void blobStaysWithinRadiusAndOnSurfaces(GameTestHelper helper) {
		stoneFloor(helper, 5);
		helper.setBlock(new BlockPos(1, 1, 2), Blocks.AIR); // a hole beside the hit, not a corner
		Painter.splat(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)), Direction.UP, PaintColor.LIME,
				helper.getLevel().getRandom());
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				BlockState cell = helper.getBlockState(new BlockPos(x, 2, z));
				boolean inBlob = Math.abs(x - 2) <= Painter.RADIUS && Math.abs(z - 2) <= Painter.RADIUS;
				boolean overHole = x == 1 && z == 2;
				if (!inBlob || overHole) {
					helper.assertTrue(cell.isAir(), Component.literal("no paint expected at " + x + "," + z + " but found " + cell));
				}
			}
		}
		helper.assertTrue(helper.getBlockState(new BlockPos(2, 2, 2)).is(PaintBlocks.of(PaintColor.LIME)), "centre is painted");
		for (BlockPos edge : new BlockPos[] {new BlockPos(3, 2, 2), new BlockPos(2, 2, 1), new BlockPos(2, 2, 3)}) {
			BlockState cell = helper.getBlockState(edge);
			helper.assertTrue(cell.is(PaintBlocks.of(PaintColor.LIME)), Component.literal("edge " + edge + " should be lime paint, got " + cell));
			helper.assertTrue(cell.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)), "edge " + edge + " has its down face set");
		}
		helper.assertTrue(helper.getBlockState(new BlockPos(1, 2, 2)).isAir(), "edge over the hole stays air");
		helper.succeed();
	}

	/** A hit in another colour recolours the whole cell and keeps its faces. */
	@GameTest
	public void otherColourRecoloursCellKeepingFaces(GameTestHelper helper) {
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE); // floor under the cell
		helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE); // wall north of the cell
		BlockPos cell = new BlockPos(2, 2, 2);
		helper.setBlock(cell, PaintBlocks.of(PaintColor.MAGENTA).defaultBlockState()
				.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
				.setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true));
		boolean painted = Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)), Direction.UP, PaintColor.LIME);
		helper.assertTrue(painted, "the cell counts as newly painted");
		BlockState after = helper.getBlockState(cell);
		helper.assertTrue(after.is(PaintBlocks.of(PaintColor.LIME)), Component.literal("cell is lime now, got " + after));
		helper.assertTrue(after.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)), "down face kept");
		helper.assertTrue(after.getValue(MultifaceBlock.getFaceProperty(Direction.NORTH)), "north face kept");
		helper.assertValueEqual(faces(after), 2, "face count");
		helper.succeed();
	}

	/** The tally counts faces per colour from the cells it tracks, and reset removes them. */
	@GameTest
	public void tallyCountsFacesAndResets(GameTestHelper helper) {
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
		helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
		helper.setBlock(new BlockPos(4, 1, 4), Blocks.STONE);
		BlockPos magentaCell = new BlockPos(2, 2, 2);
		BlockPos limeCell = new BlockPos(4, 2, 4);
		helper.setBlock(magentaCell, PaintBlocks.of(PaintColor.MAGENTA).defaultBlockState()
				.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
				.setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true));
		helper.setBlock(limeCell, PaintBlocks.of(PaintColor.LIME).defaultBlockState()
				.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
		PaintTally tally = new PaintTally();
		tally.track(helper.absolutePos(magentaCell));
		tally.track(helper.absolutePos(limeCell));
		tally.track(helper.absolutePos(new BlockPos(0, 5, 0))); // air: must be dropped, not counted
		Map<PaintColor, Integer> counts = tally.count(helper.getLevel());
		helper.assertValueEqual(counts.get(PaintColor.MAGENTA), 2, "magenta faces");
		helper.assertValueEqual(counts.get(PaintColor.LIME), 1, "lime faces");
		helper.assertValueEqual(counts.get(PaintColor.CYAN), 0, "cyan faces");
		helper.assertValueEqual(tally.cells(), 2, "the air cell was dropped");
		helper.assertTrue(Math.abs(PaintTally.share(counts, PaintColor.LIME) - 1f / 3f) < 1e-6, "lime share is a third");
		int removed = tally.reset(helper.getLevel());
		helper.assertValueEqual(removed, 2, "reset removed both cells");
		helper.assertTrue(helper.getBlockState(magentaCell).isAir() && helper.getBlockState(limeCell).isAir(), "cells are air after reset");
		helper.assertValueEqual(tally.count(helper.getLevel()).get(PaintColor.MAGENTA), 0, "nothing left to count");
		helper.succeed();
	}

	private static PlayerTeam team(GameTestHelper helper, PaintColor color) {
		ServerScoreboard board = helper.getLevel().getScoreboard();
		PlayerTeam team = board.getPlayerTeam(color.id);
		return team != null ? team : board.addPlayerTeam(color.id);
	}

	/** A mock survival player holding a gun, standing at relative (4, 3, 4), on no team. */
	private static Player gunner(GameTestHelper helper) {
		// A plain mock player, not makeMockServerPlayer: that one is a ServerPlayer with no connection,
		// so vanilla's ServerItemCooldowns throws when the gun starts its cooldown.
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		// Every mock player is called "test-mock-player" and the game test world (and its scoreboard) is
		// reused between runs, so clear any membership another test or an earlier run left behind.
		ServerScoreboard board = helper.getLevel().getScoreboard();
		if (board.getPlayersTeam(player.getScoreboardName()) != null) {
			board.removePlayerFromTeam(player.getScoreboardName());
		}
		Vec3 at = helper.absoluteVec(new Vec3(4, 3, 4));
		player.setPos(at.x, at.y, at.z);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PaintWeapon.of(Weapon.SHOOTER)));
		return player;
	}

	/** Without a team the gun refuses: no projectile, no cooldown. */
	@GameTest
	public void gunWithoutTeamDoesNotShoot(GameTestHelper helper) {
		Player player = gunner(helper);
		InteractionResult result = PaintWeapon.of(Weapon.SHOOTER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result == InteractionResult.FAIL, "use fails without a team");
		helper.assertTrue(helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0).isEmpty(), "no paint ball spawned");
		helper.assertTrue(!player.getCooldowns().isOnCooldown(player.getItemInHand(InteractionHand.MAIN_HAND)), "no cooldown");
		helper.succeed();
	}

	/** On a team the gun throws one paint ball carrying a firework star in the team colour, and starts the cooldown. */
	@GameTest
	public void gunOnTeamThrowsColouredBall(GameTestHelper helper) {
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.MAGENTA));
		InteractionResult result = PaintWeapon.of(Weapon.SHOOTER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result.consumesAction(), "use succeeds on a team");
		List<PaintBall> balls = helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0);
		helper.assertValueEqual(balls.size(), 1, "one paint ball");
		PaintBall ball = balls.getFirst();
		helper.assertTrue(ball.color() == PaintColor.MAGENTA, "ball is magenta");
		ItemStack shown = ball.getItem();
		helper.assertTrue(shown.is(Items.FIREWORK_STAR), Component.literal("ball shows a firework star, got " + shown));
		FireworkExplosion explosion = shown.get(DataComponents.FIREWORK_EXPLOSION);
		helper.assertTrue(explosion != null && explosion.colors().contains(PaintColor.MAGENTA.rgb), "star is tinted magenta");
		helper.assertTrue(player.getCooldowns().isOnCooldown(player.getItemInHand(InteractionHand.MAIN_HAND)), "cooldown started");
		ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
		PaintWeapon.of(Weapon.SHOOTER).inventoryTick(held, helper.getLevel(), player, EquipmentSlot.MAINHAND);
		DyedItemColor dye = held.get(DataComponents.DYED_COLOR);
		helper.assertTrue(dye != null && dye.rgb() == PaintColor.MAGENTA.rgb, "the held gun's tank is dyed magenta");
		balls.forEach(Entity::discard);
		helper.succeed();
	}

	/** The gun stack carries the team colour as a dye, nothing without a team, and loses a stale dye. */
	@GameTest
	public void gunTankTakesTeamColour(GameTestHelper helper) {
		ItemStack onTeam = PaintWeapon.withTankColor(new ItemStack(Items.WARPED_FUNGUS_ON_A_STICK), team(helper, PaintColor.LIME));
		DyedItemColor dye = onTeam.get(DataComponents.DYED_COLOR);
		helper.assertTrue(dye != null && dye.rgb() == PaintColor.LIME.rgb, "tank dyed lime");
		ItemStack noTeam = PaintWeapon.withTankColor(new ItemStack(Items.WARPED_FUNGUS_ON_A_STICK), null);
		helper.assertTrue(noTeam.get(DataComponents.DYED_COLOR) == null, "no dye without a team");
		ItemStack left = PaintWeapon.withTankColor(onTeam, null);
		helper.assertTrue(left.get(DataComponents.DYED_COLOR) == null, "leaving a team strips the dye");
		helper.succeed();
	}

	/** Every weapon's item definition, model and palette ship in the jar, and each model stays inside the item bounds. */
	@GameTest
	public void gunModelAssetsArePresent(GameTestHelper helper) throws IOException {
		String base = "/assets/" + Rivals.MOD_ID + "/";
		for (String id : new String[] {"paint_gun", "sprayer", "charger", "slosher"}) {
			for (String path : new String[] {"items/" + id + ".json", "models/item/" + id + ".json", "textures/item/" + id + "_palette.png"}) {
				try (InputStream in = Rivals.class.getResourceAsStream(base + path)) {
					helper.assertTrue(in != null, "asset present: " + path);
				}
			}
			try (InputStream in = Rivals.class.getResourceAsStream(base + "models/item/" + id + ".json")) {
				JsonObject model = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonArray elements = model.getAsJsonArray("elements");
				helper.assertTrue(elements.size() >= 5, id + ": model has elements");
				helper.assertTrue(elements.size() <= 400, id + ": model stays under 400 elements, got " + elements.size());
				boolean tinted = false;
				for (JsonElement e : elements) {
					JsonObject box = e.getAsJsonObject();
					for (String key : new String[] {"from", "to"}) {
						for (JsonElement v : box.getAsJsonArray(key)) {
							double d = v.getAsDouble();
							helper.assertTrue(d >= -16 && d <= 32, id + ": element coordinate in range: " + d);
						}
					}
					for (var face : box.getAsJsonObject("faces").entrySet()) {
						if (face.getValue().getAsJsonObject().has("tintindex")) tinted = true;
					}
				}
				helper.assertTrue(tinted, id + ": some faces are tinted");
			}
			try (InputStream in = Rivals.class.getResourceAsStream(base + "items/" + id + ".json")) {
				JsonObject definition = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonObject modelDef = definition.getAsJsonObject("model");
				helper.assertValueEqual(modelDef.get("model").getAsString(), Rivals.MOD_ID + ":item/" + id, id + ": definition points at the model");
				helper.assertValueEqual(modelDef.getAsJsonArray("tints").get(0).getAsJsonObject().get("type").getAsString(), "minecraft:dye", id + ": dye tint");
			}
		}
		helper.succeed();
	}

	/** A thrown ball paints the cell it lands in, on the struck face, and spends its bounce doing it. */
	@GameTest
	public void paintBallPaintsWhereItLands(GameTestHelper helper) {
		stoneFloor(helper, 5);
		PaintBall ball = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.CYAN);
		Vec3 from = helper.absoluteVec(new Vec3(2.5, 4, 2.5));
		ball.setPos(from.x, from.y, from.z);
		ball.setDeltaMovement(0, -0.6, 0); // straight down onto the floor block at relative (2, 1, 2)
		helper.getLevel().addFreshEntity(ball);
		helper.runAfterDelay(10, () -> {
			BlockPos cell = new BlockPos(2, 2, 2);
			BlockState state = helper.getBlockState(cell);
			helper.assertTrue(state.is(PaintBlocks.of(PaintColor.CYAN)),
					Component.literal("the cell where the ball landed should be cyan paint, got " + state));
			helper.assertTrue(state.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)), "paint sits on its down face");
			// A default ball carries one bounce, so ten ticks on it may still be in the air on its way
			// back down from the floor it just painted; what the hit must have spent is that bounce.
			List<PaintBall> left = helper.getEntities(PaintBall.TYPE, cell, 4.0);
			helper.assertTrue(left.stream().allMatch(other -> other.bouncesLeft() == 0),
					"the ball is gone after the hit, or has spent its bounce");
			// A bouncing ball outlives the test it was thrown in; left alone it would sail on and paint
			// into whatever test structure sits next door.
			left.forEach(Entity::discard);
			helper.succeed();
		});
	}

	/** A ball that hits a player paints the floor under them and leaves their health alone. */
	@GameTest
	public void paintBallOnEntityPaintsUnderneathWithoutDamage(GameTestHelper helper) {
		stoneFloor(helper, 5);
		Player target = helper.makeMockPlayer(GameType.SURVIVAL);
		Vec3 stand = helper.absoluteVec(new Vec3(2.5, 2, 2.5));
		target.setPos(stand.x, stand.y, stand.z);
		// The projectile's entity sweep only sees entities the level knows about.
		helper.assertTrue(helper.getLevel().addFreshEntity(target), "the target player joined the level");
		float health = target.getHealth();
		// gunner() is a different mock player: a projectile never hits its own owner.
		PaintBall ball = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.MAGENTA);
		Vec3 from = helper.absoluteVec(new Vec3(2.5, 2.6, 0.5));
		ball.setPos(from.x, from.y, from.z);
		// Flat and fast at the player's chest, not dropped on their head: a ball that missed would
		// sail off the far edge of the floor instead of splatting on the cell asserted below, so this
		// can only pass through onHitEntity.
		Direction along = helper.getAbsoluteDirection(Direction.SOUTH);
		ball.setDeltaMovement(along.getStepX() * 1.2, 0, along.getStepZ() * 1.2);
		helper.getLevel().addFreshEntity(ball);
		helper.runAfterDelay(10, () -> {
			BlockState state = helper.getBlockState(new BlockPos(2, 2, 2));
			helper.assertTrue(state.is(PaintBlocks.of(PaintColor.MAGENTA)),
					Component.literal("the floor under the player should be magenta paint, got " + state));
			helper.assertTrue(state.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)), "paint sits on its down face");
			helper.assertTrue(target.getHealth() == health,
					"the player took no damage, health " + target.getHealth() + " was " + health);
			target.discard();
			helper.succeed();
		});
	}

	/** A splash on the floor beside a wall paints the wall's face too (the rays), not only the floor. */
	@GameTest
	public void splashPaintsAdjacentWall(GameTestHelper helper) {
		stoneFloor(helper, 5);
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(4, y, 2), Blocks.STONE); // wall east of the hit
		BlockPos struck = new BlockPos(3, 1, 2);
		Vec3 impact = helper.absoluteVec(new Vec3(3.6, 2.0, 2.5));
		int changed = Painter.splash(helper.getLevel(), impact, helper.absolutePos(struck), Direction.UP, PaintColor.CYAN,
				helper.getLevel().getRandom(), null);
		helper.assertTrue(changed >= 5, "blob plus rays painted at least five cells, got " + changed);
		BlockState floorCell = helper.getBlockState(new BlockPos(3, 2, 2));
		helper.assertTrue(floorCell.is(PaintBlocks.of(PaintColor.CYAN)) && floorCell.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)),
				"floor cell painted");
		BlockState wallCell = helper.getBlockState(new BlockPos(3, 2, 2)); // same cell holds the wall's west face
		helper.assertTrue(wallCell.getValue(MultifaceBlock.getFaceProperty(Direction.EAST)),
				Component.literal("the wall face east of the hit is painted, got " + wallCell));
		helper.succeed();
	}

	/** Setup creates one vanilla team per colour with the matching colour, no friendly fire, no collisions. */
	@GameTest
	public void setupCreatesTeams(GameTestHelper helper) {
		int touched = RivalsCommands.setupTeams(helper.getLevel().getServer());
		helper.assertValueEqual(touched, PaintColor.values().length, "teams touched");
		ServerScoreboard board = helper.getLevel().getScoreboard();
		for (PaintColor color : PaintColor.values()) {
			PlayerTeam team = board.getPlayerTeam(color.id);
			helper.assertTrue(team != null, "team exists: " + color.id);
			helper.assertTrue(team.getColor().equals(Optional.of(color.teamColor)), "team colour: " + color.id);
			helper.assertTrue(!team.isAllowFriendlyFire(), "friendly fire off: " + color.id);
			helper.assertTrue(team.getCollisionRule() == Team.CollisionRule.NEVER, "no collisions: " + color.id);
		}
		helper.succeed();
	}

	/** Recoil on a mock player (no connection) sends nothing and leaves nothing queued; a shot still succeeds. */
	@GameTest
	public void recoilIsSafeWithoutConnection(GameTestHelper helper) {
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.LIME));
		int before = Recoil.pending();
		InteractionResult result = PaintWeapon.of(Weapon.SHOOTER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result.consumesAction(), "shot succeeds");
		helper.assertValueEqual(Recoil.pending(), before, "no settle queued for a connectionless player");
		helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0).forEach(Entity::discard);
		helper.succeed();
	}

	/**
	 * A stair top takes paint as display quads: tracked, counted in the colour, dropped when the surface
	 * goes (waterlogged stairs included), removed by reset.
	 */
	@GameTest
	public void stairTakesDisplayPaint(GameTestHelper helper) {
		BlockPos stair = new BlockPos(2, 1, 2);
		helper.setBlock(stair, Blocks.STONE_STAIRS.defaultBlockState());
		PaintDisplays displays = PaintDisplays.of(helper.getLevel());
		int before = displays.holders();
		boolean painted = Painter.paintFace(helper.getLevel(), helper.absolutePos(stair), Direction.UP, PaintColor.LIME);
		helper.assertTrue(painted, "stair top accepted paint");
		helper.assertTrue(helper.getBlockState(stair.above()).isAir(), "no paint block above a stair (quads instead)");
		helper.assertValueEqual(displays.holders(), before + 1, "one holder for the cell");
		helper.assertTrue(displays.colorAt(helper.absolutePos(stair.above())) == PaintColor.LIME, "cell is lime");
		helper.assertTrue(displays.count(helper.getLevel()).get(PaintColor.LIME) >= 1, "counted as lime faces");
		boolean recoloured = Painter.paintFace(helper.getLevel(), helper.absolutePos(stair), Direction.UP, PaintColor.CYAN);
		helper.assertTrue(recoloured && displays.colorAt(helper.absolutePos(stair.above())) == PaintColor.CYAN, "recoloured to cyan");
		helper.assertValueEqual(displays.holders(), before + 1, "recolour reuses the cell");
		BlockPos wet = new BlockPos(5, 1, 5);
		helper.setBlock(wet, Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(wet), Direction.UP, PaintColor.LIME),
				"a waterlogged stair still takes paint");
		// The quads die with their surface. A chunk unload does the same thing by another route — Polymer
		// destroys the holder's attachment — but a game test cannot unload its own chunks, so this half of
		// the rule stands in for both.
		helper.setBlock(stair, Blocks.AIR.defaultBlockState());
		displays.count(helper.getLevel()); // the sweep that prunes cells whose paint is gone
		helper.assertTrue(displays.colorAt(helper.absolutePos(stair.above())) == null, "the broken stair took its cell with it");
		helper.assertValueEqual(displays.holders(), before + 1, "only the waterlogged stair's holder is left");
		helper.setBlock(stair, Blocks.STONE_STAIRS.defaultBlockState());
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(stair), Direction.UP, PaintColor.CYAN),
				"a rebuilt stair takes the same colour again");
		PaintTally tally = new PaintTally();
		helper.assertTrue(tally.count(helper.getLevel()).get(PaintColor.CYAN) >= 1, "the tally counts the quads as cyan faces");
		int removed = tally.reset(helper.getLevel()); // a reset clears the level's display quads too
		helper.assertTrue(removed >= 1 && displays.holders() == 0, "clear removed the quads");
		helper.assertValueEqual(tally.count(helper.getLevel()).get(PaintColor.CYAN), 0, "nothing left to count");
		// A surface that only changes shape keeps its position, so every check above still passes, but the
		// quads were cut to the old shape and now hang over nothing: the cell must be dropped.
		BlockPos turned = new BlockPos(6, 1, 6);
		helper.setBlock(turned, Blocks.STONE_STAIRS.defaultBlockState()); // default facing is north
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(turned), Direction.UP, PaintColor.LIME),
				"the stair took paint");
		helper.assertTrue(displays.colorAt(helper.absolutePos(turned.above())) == PaintColor.LIME, "turned stair's cell is lime");
		helper.setBlock(turned, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
		displays.count(helper.getLevel()); // the sweep
		helper.assertTrue(displays.colorAt(helper.absolutePos(turned.above())) == null,
				"turning the stair under the quads dropped the cell");
		helper.assertValueEqual(displays.holders(), 0, "no holders left");
		helper.succeed();
	}

	/**
	 * The display quads' item assets are generated for every shape, and the three files agree: the item
	 * definition names the model, the model names the texture, and the dye tint reaches a tinted face.
	 */
	@GameTest
	public void quadItemAssetsAreGenerated(GameTestHelper helper) {
		Map<String, byte[]> files = SplatArt.packFiles();
		for (String shape : SplatArt.SHAPES) {
			String name = "splat_quad_" + shape;
			String itemPath = "assets/metacraft-rivals/items/" + name + ".json";
			String modelPath = "assets/metacraft-rivals/models/item/" + name + ".json";
			String texturePath = "assets/metacraft-rivals/textures/item/" + name + ".png";
			for (String path : new String[] {itemPath, modelPath, texturePath}) {
				helper.assertTrue(files.containsKey(path), "in pack: " + path);
			}
			JsonObject definition = JsonParser.parseString(new String(files.get(itemPath), StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject modelDef = definition.getAsJsonObject("model");
			String model = modelDef.get("model").getAsString(); // metacraft-rivals:item/splat_quad_xx
			String modelFile = "assets/metacraft-rivals/models/" + model.substring(model.indexOf(':') + 1) + ".json";
			helper.assertValueEqual(modelFile, modelPath, name + ": the definition points at the generated model");
			helper.assertTrue(files.containsKey(modelFile), "model in pack: " + modelFile);
			// Without the dye tint the quad renders white, whatever colour the display element carries.
			helper.assertValueEqual(modelDef.getAsJsonArray("tints").get(0).getAsJsonObject().get("type").getAsString(),
					"minecraft:dye", name + ": dye tint");
			JsonObject modelJson = JsonParser.parseString(new String(files.get(modelFile), StandardCharsets.UTF_8)).getAsJsonObject();
			boolean tinted = false;
			for (JsonElement element : modelJson.getAsJsonArray("elements")) {
				for (var face : element.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
					JsonObject json = face.getValue().getAsJsonObject();
					if (json.has("tintindex") && json.get("tintindex").getAsInt() == 0) tinted = true;
				}
			}
			helper.assertTrue(tinted, name + ": some face carries tintindex 0, so the dye reaches it");
			String texture = modelJson.getAsJsonObject("textures").get("splat").getAsString();
			String textureFile = "assets/metacraft-rivals/textures/" + texture.substring(texture.indexOf(':') + 1) + ".png";
			helper.assertValueEqual(textureFile, texturePath, name + ": the model points at the generated texture");
			helper.assertTrue(files.containsKey(textureFile), "texture in pack: " + textureFile);
		}
		helper.succeed();
	}

	/** The gloss lives in the terrain shader pair (what actually draws chunks in 26.2), keyed on the paint alpha marker. */
	@GameTest
	public void glossShaderCarriesTheMarkerGuard(GameTestHelper helper) {
		String fsh = new String(RivalsPack.shader("terrain.fsh"), StandardCharsets.UTF_8);
		String vsh = new String(RivalsPack.shader("terrain.vsh"), StandardCharsets.UTF_8);
		helper.assertTrue(fsh.contains("RIVALS_GLOSS") && fsh.contains("0.898") && fsh.contains("0.004"), "fragment shader guards on the marker alpha");
		helper.assertTrue(fsh.contains("sampleRGSS") && fsh.contains("#ifdef ALPHA_CUTOUT"), "vanilla terrain sampling and cutout kept");
		helper.assertTrue(vsh.contains("out vec3 viewPos") && vsh.contains("ChunkPosition"), "vertex shader exports the view position from the chunk-relative position");
		helper.assertTrue(RivalsPack.class.getResource("/rivals_shaders/block.fsh") == null, "the block shader override is gone");
		helper.succeed();
	}

	/** A fresh gun holds 40 ink, a shot costs one, an empty gun refills after the delay, own paint tops it up. */
	@GameTest
	public void inkDrainsRefillsAndTopsUp(GameTestHelper helper) {
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.MAGENTA));
		ItemStack gun = player.getItemInHand(InteractionHand.MAIN_HAND);
		helper.assertValueEqual(Ink.get(gun), Ink.MAX, "fresh gun is full");
		PaintWeapon.of(Weapon.SHOOTER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertValueEqual(Ink.get(gun), Ink.MAX - 1, "a shot costs one");
		Ink.set(gun, 0);
		long now = helper.getLevel().getServer().getTickCount();
		InteractionResult empty = PaintWeapon.of(Weapon.SHOOTER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(empty == InteractionResult.FAIL && Ink.isRefilling(gun, now), "empty gun starts refilling");
		Ink.finishIfDue(gun, now + Ink.REFILL_TICKS);
		helper.assertValueEqual(Ink.get(gun), Ink.MAX, "refilled after the delay");
		// A heavy weapon needs its whole shot in the tank: one ink refuses and starts a refill instead.
		ItemStack slosher = new ItemStack(PaintWeapon.of(Weapon.SLOSHER));
		player.setItemInHand(InteractionHand.MAIN_HAND, slosher);
		Ink.set(slosher, 1);
		long low = helper.getLevel().getServer().getTickCount();
		InteractionResult tooLow = PaintWeapon.of(Weapon.SLOSHER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(tooLow == InteractionResult.FAIL && Ink.isRefilling(slosher, low), "one ink does not buy a slosh");
		player.setItemInHand(InteractionHand.MAIN_HAND, gun);
		Ink.set(gun, 10);
		Ink.add(gun, 1);
		helper.assertValueEqual(Ink.get(gun), 11, "top-up adds");
		Ink.add(gun, 100);
		helper.assertValueEqual(Ink.get(gun), Ink.MAX, "top-up clamps");
		// A deadline saved before a restart: the server tick count starts again at 0, so what was
		// "30 ticks from now" comes back as a deadline far in the future and would leave the gun
		// refilling for the rest of the session. Written straight into the tag, as loading would.
		Ink.set(gun, 0);
		CustomData.update(DataComponents.CUSTOM_DATA, gun, tag -> tag.putLong("rivals_refill_until", now + 100000L));
		helper.assertTrue(!Ink.isRefilling(gun, now), "a deadline from before a restart is not a refill in progress");
		Ink.finishIfDue(gun, now);
		helper.assertValueEqual(Ink.get(gun), Ink.MAX, "the stale deadline completes the refill instead of bricking the gun");
		helper.assertTrue(!Ink.isRefilling(gun, now), "and the deadline is gone");
		helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0).forEach(Entity::discard);
		helper.succeed();
	}

	/** The action-bar text has ten cells, one per four ink, and says REFILLING while a refill runs. */
	@GameTest
	public void inkBarText(GameTestHelper helper) {
		String full = InkHud.bar(PaintColor.LIME, Ink.MAX, false, false).getString();
		helper.assertTrue(full.startsWith("INK ") && full.contains("40/40") && full.chars().filter(c -> c == '\u2588').count() == 10, "full bar: " + full);
		String half = InkHud.bar(PaintColor.LIME, 20, false, false).getString();
		helper.assertTrue(half.chars().filter(c -> c == '\u2588').count() == 5 && half.chars().filter(c -> c == '\u2591').count() == 5, "half bar: " + half);
		helper.assertTrue(InkHud.bar(PaintColor.LIME, 0, true, false).getString().contains("REFILLING"), "refilling text");
		helper.assertTrue(InkHud.bar(PaintColor.LIME, 5, false, true).getString().contains("SQUID"), "squid tag");
		// No team is still a real tank: same text, grey instead of a team colour.
		Component noTeam = InkHud.bar(null, Ink.MAX, false, false);
		helper.assertValueEqual(noTeam.getString(), full, "the no-team bar reads the same");
		helper.assertTrue(TextColor.fromLegacyFormat(ChatFormatting.GRAY).equals(noTeam.getStyle().getColor()),
				"no team: the bar is grey, got " + noTeam.getStyle().getColor());
		helper.succeed();
	}

	/** Sneaking on own paint is squid form (invisible, fast, no shooting); standing on enemy paint slows. */
	@GameTest
	public void squidFormAndEnemySlowness(GameTestHelper helper) {
		helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE);
		Player player = gunner(helper); // stands at relative (4, 3, 4), i.e. in the cell above that stone
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.MAGENTA));
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), Direction.UP, PaintColor.MAGENTA);
		helper.assertTrue(PlayerTick.paintUnder(player) == PaintColor.MAGENTA, "own paint under the player");
		player.setShiftKeyDown(true);
		PlayerTick.tick(player, 0);
		helper.assertTrue(PlayerTick.isSquid(player), "squid form on");
		helper.assertTrue(player.hasEffect(MobEffects.INVISIBILITY), "invisible");
		AttributeInstance scale = player.getAttribute(Attributes.SCALE);
		helper.assertTrue(scale != null && scale.hasModifier(SquidState.SCALE_ID) && scale.getValue() < 0.6, "squid is half size");
		helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(SquidState.SPEED_ID), "squid is fast");
		helper.assertTrue(player.getAttribute(Attributes.JUMP_STRENGTH).hasModifier(SquidState.JUMP_ID), "squid hops");
		helper.assertTrue(player.getAttribute(Attributes.STEP_HEIGHT).hasModifier(SquidState.STEP_ID), "squid glides over steps");
		// A fresh effect ticks down for real, one server tick at a time. Re-applying it here should not
		// reset it back to full: it is still well above the running-low threshold, so `keep` must leave it.
		MobEffectInstance invisibility = player.getEffect(MobEffects.INVISIBILITY);
		int firstDuration = invisibility.getDuration();
		invisibility.tickServer(helper.getLevel(), player, () -> {});
		PlayerTick.tick(player, 0);
		int secondDuration = player.getEffect(MobEffects.INVISIBILITY).getDuration();
		helper.assertTrue(secondDuration == firstDuration - 1,
				"effect ticks down instead of resetting to full: first=" + firstDuration + " second=" + secondDuration);
		InteractionResult shot = PaintWeapon.of(Weapon.SHOOTER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(shot == InteractionResult.FAIL, "no shooting as a squid");
		player.setShiftKeyDown(false);
		PlayerTick.tick(player, 1);
		helper.assertTrue(!PlayerTick.isSquid(player), "squid form off when not sneaking");
		helper.assertTrue(!player.getAttribute(Attributes.SCALE).hasModifier(SquidState.SCALE_ID)
				&& !player.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(SquidState.SPEED_ID), "modifiers removed on exit");
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), Direction.UP, PaintColor.LIME);
		PlayerTick.tick(player, 2);
		helper.assertTrue(player.hasEffect(MobEffects.SLOWNESS), "enemy paint slows");
		helper.assertValueEqual(player.getEffect(MobEffects.SLOWNESS).getAmplifier(), 1, "Slowness II");
		helper.assertTrue(player.getAttribute(Attributes.JUMP_STRENGTH).hasModifier(SquidState.NO_JUMP_ID), "enemy ink kills the jump");
		helper.succeed();
	}

	/**
	 * A bottom slab's paint lands as display quads keyed one cell above the slab (like a stair tread), but
	 * a player standing on the slab has {@code blockPosition()} at the slab's own cell, one below that.
	 * {@code paintUnder} must still find it by falling back to the cell above the feet.
	 */
	@GameTest
	public void squidDetectsPaintOnSlabTread(GameTestHelper helper) {
		BlockPos slab = new BlockPos(4, 2, 4);
		helper.setBlock(slab, Blocks.STONE_SLAB.defaultBlockState());
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		Vec3 at = helper.absoluteVec(new Vec3(4.5, 2.5, 4.5)); // standing on top of the bottom slab
		player.setPos(at.x, at.y, at.z);
		boolean painted = Painter.paintFace(helper.getLevel(), helper.absolutePos(slab), Direction.UP, PaintColor.MAGENTA);
		helper.assertTrue(painted, "slab top accepted paint");
		helper.assertTrue(PaintDisplays.of(helper.getLevel()).colorAt(helper.absolutePos(slab)) == null,
				"quads are keyed one cell above the slab, not at the slab's own cell");
		helper.assertTrue(PlayerTick.paintUnder(player) == PaintColor.MAGENTA,
				"paint on the slab tread is found from the player's feet cell below it");
		helper.succeed();
	}

	/** Pushing against an own-colour painted wall while a squid lifts the player. */
	@GameTest
	public void squidWallSwim(GameTestHelper helper) {
		stoneFloor(helper, 5);
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(4, y, 2), Blocks.STONE);
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.MAGENTA));
		Vec3 at = helper.absoluteVec(new Vec3(3.5, 2.0, 2.5));
		player.setPos(at.x, at.y, at.z);
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 2)), Direction.UP, PaintColor.MAGENTA); // floor under
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 2)), Direction.WEST, PaintColor.MAGENTA); // wall beside
		player.setShiftKeyDown(true);
		player.horizontalCollision = true;
		player.setDeltaMovement(0.1, 0, 0);
		PlayerTick.tick(player, 0);
		helper.assertTrue(SquidState.isSquid(player), "squid");
		helper.assertTrue(player.getDeltaMovement().y > 0.2, "lifted up the inked wall, dy=" + player.getDeltaMovement().y);
		// Only floor paint left: the wall itself carries no paint, so the squid must not climb it.
		helper.setBlock(new BlockPos(4, 2, 2), Blocks.AIR);
		player.horizontalCollision = true;
		player.setDeltaMovement(0.1, 0, 0);
		PlayerTick.tick(player, 1);
		helper.assertTrue(player.getDeltaMovement().y < 0.2, "not lifted without a painted wall, dy=" + player.getDeltaMovement().y);
		helper.succeed();
	}

	/** Enemy ink drips: 1 damage every 20 ticks in survival, never below 1 health. */
	@GameTest
	public void enemyInkDripDamage(GameTestHelper helper) {
		helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE);
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.MAGENTA));
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), Direction.UP, PaintColor.LIME);
		float before = player.getHealth();
		PlayerTick.tick(player, 20);
		helper.assertTrue(player.getHealth() <= before - 1.0f, "hurt on a damage tick, health " + player.getHealth());
		// The guard itself, isolated from vanilla's post-hit invulnerability: at 1.5 health a drip would
		// take the player below one, so it must not land at all.
		player.setHealth(1.5f);
		player.invulnerableTime = 0;
		PlayerTick.tick(player, 40);
		helper.assertTrue(player.getHealth() == 1.5f, "guard skips the drip below one health, health " + player.getHealth());
		// At 3.0 a drip lands as normal.
		player.setHealth(3.0f);
		player.invulnerableTime = 0;
		PlayerTick.tick(player, 60);
		helper.assertTrue(player.getHealth() == 2.0f, "drip lands with health to spare, health " + player.getHealth());
		// Never below one health: without resetting invulnerableTime this would be vacuous, since vanilla
		// itself rejects a second hit within the previous drip's invulnerability window.
		player.setHealth(1.5f);
		player.invulnerableTime = 0;
		PlayerTick.tick(player, 80);
		helper.assertTrue(player.getHealth() == 1.5f, "never below one health, health " + player.getHealth());
		player.setHealth(before);
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void paintBallBouncesOnce(GameTestHelper helper) {
		stoneFloor(helper, 5);
		PaintBall ball = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.CYAN, 1, 0);
		Vec3 at = helper.absoluteVec(new Vec3(2.5, 4, 2.5));
		ball.setPos(at.x, at.y, at.z);
		ball.setDeltaMovement(0, -0.8, 0);
		helper.getLevel().addFreshEntity(ball);
		helper.runAfterDelay(6, () -> {
			helper.assertTrue(!ball.isRemoved(), "still flying after the first impact");
			helper.assertValueEqual(ball.bouncesLeft(), 0, "one bounce used");
			helper.assertTrue(helper.getBlockState(new BlockPos(2, 2, 2)).is(PaintBlocks.of(PaintColor.CYAN)), "first impact painted");
		});
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(ball.isRemoved(), "gone after the second impact");
			helper.succeed();
		});
	}

	/** A sprayer droplet with a 12-tick lifetime splashes the floor beneath it when time runs out. */
	@GameTest
	public void dropletSplashesAfterLifetime(GameTestHelper helper) {
		stoneFloor(helper, 5);
		PaintBall drop = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.LIME, 0, 12);
		Vec3 at = helper.absoluteVec(new Vec3(2.5, 3.2, 2.5));
		drop.setPos(at.x, at.y, at.z);
		drop.setDeltaMovement(0, 0.02, 0); // hovering: only the lifetime can end it
		drop.setNoGravity(true);
		helper.getLevel().addFreshEntity(drop);
		helper.runAfterDelay(16, () -> {
			helper.assertTrue(drop.isRemoved(), "droplet expired");
			helper.assertTrue(helper.getBlockState(new BlockPos(2, 2, 2)).is(PaintBlocks.of(PaintColor.LIME)), "floor under the droplet painted");
			helper.succeed();
		});
	}

	/** The blob display follows the ball and is torn down with it. */
	@GameTest
	public void blobFollowsTheBall(GameTestHelper helper) {
		PaintBall ball = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.MAGENTA, 0, 0);
		Vec3 at = helper.absoluteVec(new Vec3(2.5, 5, 2.5));
		ball.setPos(at.x, at.y, at.z);
		ball.setNoGravity(true);
		helper.getLevel().addFreshEntity(ball);
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(ball.blobHolder() != null && ball.blobHolder().getAttachment() != null, "blob attached while flying");
			ball.discard();
		});
		helper.runAfterDelay(4, () -> {
			helper.assertTrue(ball.blobHolder() == null || ball.blobHolder().getAttachment() == null, "blob gone with the ball");
			helper.succeed();
		});
	}

	/** One click of the sprayer throws three short-lived droplets for one ink. */
	@GameTest
	public void sprayerThrowsThreeDroplets(GameTestHelper helper) {
		Player player = gunner(helper);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PaintWeapon.of(Weapon.SPRAYER)));
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.LIME));
		InteractionResult result = PaintWeapon.of(Weapon.SPRAYER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result.consumesAction(), "sprays");
		List<PaintBall> drops = helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0);
		helper.assertValueEqual(drops.size(), 3, "three droplets");
		for (PaintBall drop : drops) {
			helper.assertValueEqual(drop.bouncesLeft(), 0, "droplets do not bounce");
			helper.assertValueEqual(drop.splatRadius(), 0, "droplets paint a single face");
		}
		helper.assertValueEqual(Ink.get(player.getItemInHand(InteractionHand.MAIN_HAND)), Ink.MAX - Weapon.SPRAYER.inkPerShot, "ink cost");
		drops.forEach(Entity::discard);
		helper.succeed();
	}

	/**
	 * One click of the slosher throws four balls in a fan: four distinct horizontal directions, a 5x5
	 * splat radius each, and the slosher is the one weapon whose use swings the arm.
	 */
	@GameTest
	public void slosherThrowsFourInAFan(GameTestHelper helper) {
		Player player = gunner(helper);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PaintWeapon.of(Weapon.SLOSHER)));
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.CYAN));
		InteractionResult result = PaintWeapon.of(Weapon.SLOSHER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result.consumesAction(), "sloshes");
		helper.assertTrue(result == InteractionResult.SUCCESS_SERVER, "the slosher swings the arm, got " + result);
		List<PaintBall> balls = helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0);
		helper.assertValueEqual(balls.size(), 4, "four balls");
		List<Double> yaws = new ArrayList<>();
		for (PaintBall ball : balls) {
			helper.assertValueEqual(ball.splatRadius(), 2, "5x5 splat");
			helper.assertValueEqual(ball.bouncesLeft(), 0, "no bounce");
			Vec3 v = ball.getDeltaMovement();
			helper.assertTrue(v.y > 0, "the slosh is lobbed, not thrown flat: " + v.y);
			yaws.add(Math.atan2(-v.x, v.z));
		}
		for (int i = 0; i < yaws.size(); i++) {
			for (int j = i + 1; j < yaws.size(); j++) {
				helper.assertTrue(Math.abs(yaws.get(i) - yaws.get(j)) > 1.0e-4,
						"the four balls fan out: " + yaws.get(i) + " vs " + yaws.get(j));
			}
		}
		helper.assertValueEqual(Ink.get(player.getItemInHand(InteractionHand.MAIN_HAND)), Ink.MAX - Weapon.SLOSHER.inkPerShot, "ink cost");
		balls.forEach(Entity::discard);
		helper.succeed();
	}

	/** The kit hands out one of every weapon. */
	@GameTest
	public void kitGivesEveryWeapon(GameTestHelper helper) {
		Player player = gunner(helper);
		player.getInventory().clearContent();
		int given = PaintWeapon.giveKit(player);
		helper.assertValueEqual(given, Weapon.values().length, "one of each");
		for (Weapon weapon : Weapon.values()) {
			helper.assertTrue(player.getInventory().contains(new ItemStack(PaintWeapon.of(weapon))), "has " + weapon.id);
			helper.assertTrue(Weapon.byId(weapon.id).orElse(null) == weapon, "byId round-trips " + weapon.id);
		}
		helper.assertTrue(Weapon.byId("nonesuch").isEmpty(), "an unknown id resolves to nothing");
		player.getInventory().clearContent();
		helper.succeed();
	}

	/** Releasing a charged charger paints the floor under the scanned line and splats where it ends. */
	@GameTest
	public void chargerPaintsALineUnderTheScan(GameTestHelper helper) {
		stoneFloor(helper, 7); // floor at y=1, x/z 0..6
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(6, y, 3), Blocks.STONE); // end wall
		Player player = gunner(helper);
		ItemStack charger = new ItemStack(PaintWeapon.of(Weapon.CHARGER));
		player.setItemInHand(InteractionHand.MAIN_HAND, charger);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.CYAN));
		Vec3 at = helper.absoluteVec(new Vec3(0.5, 2.0, 3.5));
		player.setPos(at.x, at.y, at.z);
		player.setYRot(-90f); // look +X
		player.setXRot(0f);
		boolean fired = PaintWeapon.of(Weapon.CHARGER).releaseUsing(charger, helper.getLevel(), player, 72000 - PaintWeapon.CHARGE_FULL_TICKS);
		helper.assertTrue(fired, "full charge fires");
		int painted = 0;
		for (int x = 1; x <= 5; x++) {
			if (helper.getBlockState(new BlockPos(x, 2, 3)).is(PaintBlocks.of(PaintColor.CYAN))) painted++;
		}
		helper.assertTrue(painted >= 3, "floor painted along the line, got " + painted);
		helper.assertTrue(helper.getBlockState(new BlockPos(5, 2, 3)).getValue(MultifaceBlock.getFaceProperty(Direction.EAST)), "end wall splatted");
		helper.assertValueEqual(Ink.get(charger), Ink.MAX - 12, "full charge costs 12");
		helper.succeed();
	}

	/** A tap is not a charge: nothing fires and no ink is spent. */
	@GameTest
	public void chargerIgnoresShortRelease(GameTestHelper helper) {
		Player player = gunner(helper);
		ItemStack charger = new ItemStack(PaintWeapon.of(Weapon.CHARGER));
		player.setItemInHand(InteractionHand.MAIN_HAND, charger);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.CYAN));
		boolean fired = PaintWeapon.of(Weapon.CHARGER).releaseUsing(charger, helper.getLevel(), player, 72000 - 2);
		helper.assertTrue(!fired && Ink.get(charger) == Ink.MAX, "a tap does nothing and costs nothing");
		helper.succeed();
	}
}
