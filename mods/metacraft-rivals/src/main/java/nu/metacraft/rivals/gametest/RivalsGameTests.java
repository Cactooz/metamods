package nu.metacraft.rivals.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.RivalsCommands;
import nu.metacraft.rivals.gun.PaintBall;
import nu.metacraft.rivals.gun.PaintGun;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PaintGun.ITEM));
		return player;
	}

	/** Without a team the gun refuses: no projectile, no cooldown. */
	@GameTest
	public void gunWithoutTeamDoesNotShoot(GameTestHelper helper) {
		Player player = gunner(helper);
		InteractionResult result = PaintGun.ITEM.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
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
		InteractionResult result = PaintGun.ITEM.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result == InteractionResult.SUCCESS, "use succeeds on a team");
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
		PaintGun.ITEM.inventoryTick(held, helper.getLevel(), player, EquipmentSlot.MAINHAND);
		DyedItemColor dye = held.get(DataComponents.DYED_COLOR);
		helper.assertTrue(dye != null && dye.rgb() == PaintColor.MAGENTA.rgb, "the held gun's tank is dyed magenta");
		balls.forEach(Entity::discard);
		helper.succeed();
	}

	/** The gun stack carries the team colour as a dye, nothing without a team, and loses a stale dye. */
	@GameTest
	public void gunTankTakesTeamColour(GameTestHelper helper) {
		ItemStack onTeam = PaintGun.withTankColor(new ItemStack(Items.WARPED_FUNGUS_ON_A_STICK), team(helper, PaintColor.LIME));
		DyedItemColor dye = onTeam.get(DataComponents.DYED_COLOR);
		helper.assertTrue(dye != null && dye.rgb() == PaintColor.LIME.rgb, "tank dyed lime");
		ItemStack noTeam = PaintGun.withTankColor(new ItemStack(Items.WARPED_FUNGUS_ON_A_STICK), null);
		helper.assertTrue(noTeam.get(DataComponents.DYED_COLOR) == null, "no dye without a team");
		ItemStack left = PaintGun.withTankColor(onTeam, null);
		helper.assertTrue(left.get(DataComponents.DYED_COLOR) == null, "leaving a team strips the dye");
		helper.succeed();
	}

	/** The gun's item definition, model and palette ship in the jar, and the model stays inside the item bounds. */
	@GameTest
	public void gunModelAssetsArePresent(GameTestHelper helper) throws IOException {
		String base = "/assets/" + Rivals.MOD_ID + "/";
		for (String path : new String[] {"items/paint_gun.json", "models/item/paint_gun.json", "textures/item/paint_gun_palette.png"}) {
			try (InputStream in = Rivals.class.getResourceAsStream(base + path)) {
				helper.assertTrue(in != null, "asset present: " + path);
			}
		}
		try (InputStream in = Rivals.class.getResourceAsStream(base + "models/item/paint_gun.json")) {
			JsonObject model = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray elements = model.getAsJsonArray("elements");
			helper.assertTrue(elements.size() >= 5, "model has elements");
			boolean tinted = false;
			for (JsonElement e : elements) {
				JsonObject box = e.getAsJsonObject();
				for (String key : new String[] {"from", "to"}) {
					for (JsonElement v : box.getAsJsonArray(key)) {
						double d = v.getAsDouble();
						helper.assertTrue(d >= -16 && d <= 32, "element coordinate in range: " + d);
					}
				}
				for (var face : box.getAsJsonObject("faces").entrySet()) {
					if (face.getValue().getAsJsonObject().has("tintindex")) tinted = true;
				}
			}
			helper.assertTrue(tinted, "some faces are tinted (the tank)");
		}
		try (InputStream in = Rivals.class.getResourceAsStream(base + "items/paint_gun.json")) {
			JsonObject definition = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject modelDef = definition.getAsJsonObject("model");
			helper.assertValueEqual(modelDef.get("model").getAsString(), Rivals.MOD_ID + ":item/paint_gun", "definition points at the model");
			helper.assertValueEqual(modelDef.getAsJsonArray("tints").get(0).getAsJsonObject().get("type").getAsString(), "minecraft:dye", "dye tint");
		}
		helper.succeed();
	}

	/** A thrown ball paints the cell it lands in, on the struck face, and is gone afterwards. */
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
			helper.assertTrue(helper.getEntities(PaintBall.TYPE, cell, 4.0).isEmpty(), "the ball is gone after the hit");
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
		InteractionResult result = PaintGun.ITEM.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result == InteractionResult.SUCCESS, "shot succeeds");
		helper.assertValueEqual(Recoil.pending(), before, "no settle queued for a connectionless player");
		helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0).forEach(Entity::discard);
		helper.succeed();
	}

	/** A stair top takes paint as display quads: tracked, counted in the colour, removed by reset. */
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
		helper.assertTrue(displays.count().get(PaintColor.LIME) >= 1, "counted as lime faces");
		boolean recoloured = Painter.paintFace(helper.getLevel(), helper.absolutePos(stair), Direction.UP, PaintColor.CYAN);
		helper.assertTrue(recoloured && displays.colorAt(helper.absolutePos(stair.above())) == PaintColor.CYAN, "recoloured to cyan");
		helper.assertValueEqual(displays.holders(), before + 1, "recolour reuses the cell");
		PaintTally tally = new PaintTally();
		helper.assertTrue(tally.count(helper.getLevel()).get(PaintColor.CYAN) >= 1, "the tally counts the quads as cyan faces");
		int removed = tally.reset(helper.getLevel()); // a reset clears the level's display quads too
		helper.assertTrue(removed >= 1 && displays.holders() == 0, "clear removed the quads");
		helper.assertValueEqual(tally.count(helper.getLevel()).get(PaintColor.CYAN), 0, "nothing left to count");
		helper.succeed();
	}
}
