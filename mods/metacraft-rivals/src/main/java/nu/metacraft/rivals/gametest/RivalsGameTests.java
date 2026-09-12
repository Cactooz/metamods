package nu.metacraft.rivals.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import nu.metacraft.rivals.PaintColor;
import nu.metacraft.rivals.OvveTeams;
import nu.metacraft.rivals.PlayerTick;
import nu.metacraft.rivals.Rivals;
import nu.metacraft.rivals.SquidDisplay;
import nu.metacraft.rivals.SquidState;
import nu.metacraft.rivals.RivalsCommands;
import nu.metacraft.rivals.gun.PaintBall;
import nu.metacraft.rivals.gun.PaintWeapon;
import nu.metacraft.rivals.gun.Weapon;
import nu.metacraft.rivals.gun.WeaponTuning;
import nu.metacraft.rivals.gun.WeaponTuning.Param;
import nu.metacraft.rivals.gun.Recoil;
import nu.metacraft.rivals.paint.ConnectedPaintBlock;
import nu.metacraft.rivals.paint.Paint;
import nu.metacraft.rivals.paint.PaintBlock;
import nu.metacraft.rivals.paint.PaintBlocks;
import nu.metacraft.rivals.paint.PaintDisplays;
import nu.metacraft.rivals.paint.Painter;
import nu.metacraft.rivals.paint.PaintTally;
import nu.metacraft.rivals.pack.InkArt;
import nu.metacraft.rivals.pack.PaintArt;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nu.metacraft.rivals.pack.RivalsPack;
import nu.metacraft.rivals.gun.Ink;
import nu.metacraft.rivals.gun.InkHud;
import nu.metacraft.rivals.gun.InkOnScreen;
import nu.metacraft.rivals.paint.PaintStates;

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

	/** Both paint blocks are sent as the client state the table (spec §2) keeps for them, and never as water. */
	@GameTest
	public void paintMapsThroughTheStateTable(GameTestHelper helper) {
		for (PaintColor color : PaintColor.values()) {
			PaintBlock splat = PaintBlocks.splat(color);
			int mask = 1 << Direction.DOWN.ordinal() | 1 << Direction.NORTH.ordinal();
			BlockState state = splat.defaultBlockState()
					.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
					.setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true);
			helper.assertValueEqual(splat.faceMask(state), mask, color.id + " splat face mask");
			BlockState client = splat.getPolymerBlockState(state, PacketContext.get());
			helper.assertValueEqual(client, PaintStates.splat(color, mask), color.id + " splat maps to its own client state");
			ConnectedPaintBlock connected = PaintBlocks.connected(color);
			BlockState cell = ConnectedPaintBlock.withBits(
					connected.defaultBlockState().setValue(ConnectedPaintBlock.FACE, Direction.NORTH), 0b1010);
			helper.assertValueEqual(ConnectedPaintBlock.bits(cell), 0b1010, color.id + " connection bits round-trip");
			helper.assertValueEqual(connected.faceMask(cell), 1 << Direction.NORTH.ordinal(), color.id + " connected face mask");
			BlockState connectedClient = connected.getPolymerBlockState(cell, PacketContext.get());
			helper.assertValueEqual(connectedClient, PaintStates.connected(color, Direction.NORTH, 0b1010),
					color.id + " connected cell maps to its own client state");
			for (BlockState sent : List.of(client, connectedClient)) {
				helper.assertTrue(!sent.hasProperty(BlockStateProperties.WATERLOGGED) || !sent.getValue(BlockStateProperties.WATERLOGGED),
						Component.literal(color.id + " sent waterlogged: " + sent));
			}
		}
		helper.succeed();
	}

	/**
	 * Every generated paint texture is 16×16 and every one of its texels is the same ARGB value: the
	 * alpha marker the gloss shader reads, the connection bits in the low nibble of red, the colour in
	 * the rest. Uniform sprites are what makes reading bits back out of a mipmapped texel safe.
	 */
	@GameTest
	public void paintArtCarriesTheMarkerAlpha(GameTestHelper helper) throws IOException {
		Map<String, byte[]> files = PaintArt.packFiles();
		for (PaintColor color : PaintColor.values()) {
			for (int bits = 0; bits < 16; bits++) {
				String path = "assets/metacraft-rivals/textures/block/" + PaintArt.textureName(color, bits) + ".png";
				helper.assertTrue(files.containsKey(path), "texture in pack: " + path);
				BufferedImage image = ImageIO.read(new ByteArrayInputStream(files.get(path)));
				helper.assertValueEqual(image.getWidth(), PaintArt.SIZE, path + " width");
				helper.assertValueEqual(image.getHeight(), PaintArt.SIZE, path + " height");
				int first = image.getRGB(0, 0);
				for (int y = 0; y < image.getHeight(); y++) {
					for (int x = 0; x < image.getWidth(); x++) {
						helper.assertValueEqual((image.getRGB(x, y) >>> 24) & 0xFF, PaintArt.PAINT_ALPHA, path + " alpha at " + x + "," + y);
						helper.assertValueEqual(image.getRGB(x, y), first, path + " uniform at " + x + "," + y);
					}
				}
				helper.assertValueEqual((first >> 16) & 0xFF, PaintArt.encodeRed(color.rgb, bits), path + " red carries the bits");
				helper.assertValueEqual(first & 0xFFFF, color.rgb & 0xFFFF, path + " green and blue are the colour's own");
			}
		}
		helper.succeed();
	}

	/**
	 * Every donor override covers every state of its block, names a model that is in the pack, and each
	 * model resolves to a texture that is in the pack too — states paint does not use point at the empty
	 * model, so a stray vanilla sculk vein shows nothing. Every model carries a particle texture, and the
	 * shared face quad is the shape the shader expects.
	 */
	@GameTest
	public void blockstateOverridesReferenceGeneratedModels(GameTestHelper helper) {
		Map<String, byte[]> files = PaintArt.packFiles();
		Set<BlockState> used = new HashSet<>(PaintStates.all());
		for (Block donor : PaintStates.DONORS) {
			String path = "assets/minecraft/blockstates/" + BuiltInRegistries.BLOCK.getKey(donor).getPath() + ".json";
			helper.assertTrue(files.containsKey(path), "override present: " + path);
			JsonObject variants = JsonParser.parseString(new String(files.get(path), StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonObject("variants");
			int states = 0;
			for (BlockState state : donor.getStateDefinition().getPossibleStates()) {
				states++;
				JsonElement variant = variants.get(PaintArt.variantKey(state));
				helper.assertTrue(variant != null, "variant for " + state);
				String model = variant.getAsJsonObject().get("model").getAsString(); // metacraft-rivals:block/paint_...
				String modelPath = "assets/metacraft-rivals/models/block/" + model.substring(model.indexOf('/') + 1) + ".json";
				helper.assertTrue(files.containsKey(modelPath), "model in pack: " + modelPath);
				JsonObject json = JsonParser.parseString(new String(files.get(modelPath), StandardCharsets.UTF_8)).getAsJsonObject();
				// Every model needs a particle texture, empty ones included, or the client logs a missing
				// texture reference for it on every join.
				helper.assertTrue(json.getAsJsonObject("textures").has("particle"), "particle texture in " + modelPath);
				if (!used.contains(state)) {
					helper.assertValueEqual(model, Rivals.MOD_ID + ":block/paint_none", "unused donor state draws nothing: " + state);
					helper.assertValueEqual(json.getAsJsonArray("elements").size(), 0, modelPath + " draws nothing");
					continue;
				}
				String texture = json.getAsJsonObject("textures").get("paint").getAsString();
				String texturePath = "assets/metacraft-rivals/textures/" + texture.substring(texture.indexOf(':') + 1) + ".png";
				helper.assertTrue(files.containsKey(texturePath), "texture in pack: " + texturePath);
				if (json.has("parent")) {
					// A connected cell: the wrapper hangs its texture on one of the six shared face quads.
					String parent = json.get("parent").getAsString();
					String parentPath = "assets/metacraft-rivals/models/block/" + parent.substring(parent.indexOf('/') + 1) + ".json";
					helper.assertTrue(files.containsKey(parentPath), "parent model in pack: " + parentPath);
					JsonObject parentJson = JsonParser.parseString(new String(files.get(parentPath), StandardCharsets.UTF_8)).getAsJsonObject();
					helper.assertValueEqual(parentJson.getAsJsonArray("elements").size(), 1, parentPath + " is one quad");
				} else {
					// A splat mask: one model listing a quad per painted face, all on the all-connected texture.
					helper.assertValueEqual(json.getAsJsonArray("elements").size(),
							Integer.bitCount(PaintStates.entry(state).faceMask()), modelPath + " has a quad per painted face");
				}
			}
			helper.assertValueEqual(variants.size(), states, donor + ": a variant for every state");
		}
		// The quad itself: a plane the full 16×16 of the cell, a tenth of a sixteenth off the attach face
		// (vanilla's own multiface offset), textured on both of its sides with the whole sprite and never
		// tinted — a slab, a smaller uv or a tintindex would each change what the shader is handed.
		String facePath = "assets/metacraft-rivals/models/block/" + PaintArt.modelName(Direction.DOWN) + ".json";
		JsonObject face = JsonParser.parseString(new String(files.get(facePath), StandardCharsets.UTF_8)).getAsJsonObject();
		JsonArray elements = face.getAsJsonArray("elements");
		helper.assertValueEqual(elements.size(), 1, facePath + " is one quad");
		JsonObject element = elements.get(0).getAsJsonObject();
		JsonArray from = element.getAsJsonArray("from");
		JsonArray to = element.getAsJsonArray("to");
		helper.assertValueEqual(from.get(1).getAsDouble(), 0.1, facePath + " sits 0.1 off the down face");
		helper.assertValueEqual(to.get(1).getAsDouble(), from.get(1).getAsDouble(), facePath + " is a plane, not a slab");
		for (int axis : new int[] {0, 2}) {
			helper.assertValueEqual(from.get(axis).getAsDouble(), 0.0, facePath + " starts at 0 on axis " + axis);
			helper.assertValueEqual(to.get(axis).getAsDouble(), 16.0, facePath + " spans the cell on axis " + axis);
		}
		JsonObject faces = element.getAsJsonObject("faces");
		helper.assertValueEqual(faces.keySet(), Set.of("up", "down"), facePath + ": both sides of the quad, and only those");
		for (String side : faces.keySet()) {
			JsonObject json = faces.getAsJsonObject(side);
			helper.assertValueEqual(json.get("texture").getAsString(), "#paint", facePath + " " + side + " texture");
			helper.assertTrue(!json.has("tintindex"), facePath + " " + side + " is untinted");
			JsonArray uv = json.getAsJsonArray("uv");
			helper.assertValueEqual(uv.toString(), "[0,0,16,16]", facePath + " " + side + " uv covers the sprite");
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
		return state.getBlock() instanceof Paint paint ? Integer.bitCount(paint.faceMask(state)) : 0;
	}

	/** Paint of {@code color} in this cell, of either kind (a connected cell or the splat fallback). */
	private static boolean isPaint(BlockState state, PaintColor color) {
		return state.getBlock() instanceof Paint paint && paint.color() == color;
	}

	/** The same, carrying paint on the {@code face} side of the cell. */
	private static boolean hasFace(BlockState state, PaintColor color, Direction face) {
		return state.getBlock() instanceof Paint paint && paint.color() == color
				&& (paint.faceMask(state) & 1 << face.ordinal()) != 0;
	}

	/** A splat on the top of a floor block paints the cell above it, on its down face, in that colour. */
	@GameTest
	public void floorSplatPaintsCellAbove(GameTestHelper helper) {
		stoneFloor(helper, 5);
		BlockPos struck = new BlockPos(2, 1, 2);
		int painted = Painter.splat(helper.getLevel(), helper.absolutePos(struck), Direction.UP, PaintColor.DATA,
				helper.getLevel().getRandom());
		helper.assertTrue(painted >= 5 && painted <= 9, "painted " + painted + " faces, expected 5..9");
		BlockState cell = helper.getBlockState(struck.above());
		helper.assertTrue(isPaint(cell, PaintColor.DATA), Component.literal("cell above the hit is DATA paint, got " + cell));
		helper.assertTrue(hasFace(cell, PaintColor.DATA, Direction.DOWN), "paint sits on its down face");
		helper.succeed();
	}

	/** The blob stays within one block of the hit in the plane, and never where the surface is missing. */
	@GameTest
	public void blobStaysWithinRadiusAndOnSurfaces(GameTestHelper helper) {
		stoneFloor(helper, 5);
		helper.setBlock(new BlockPos(1, 1, 2), Blocks.AIR); // a hole beside the hit, not a corner
		Painter.splat(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)), Direction.UP, PaintColor.DATA,
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
		helper.assertTrue(isPaint(helper.getBlockState(new BlockPos(2, 2, 2)), PaintColor.DATA), "centre is painted");
		for (BlockPos edge : new BlockPos[] {new BlockPos(3, 2, 2), new BlockPos(2, 2, 1), new BlockPos(2, 2, 3)}) {
			BlockState cell = helper.getBlockState(edge);
			helper.assertTrue(isPaint(cell, PaintColor.DATA), Component.literal("edge " + edge + " should be DATA paint, got " + cell));
			helper.assertTrue(hasFace(cell, PaintColor.DATA, Direction.DOWN), "edge " + edge + " has its down face set");
		}
		helper.assertTrue(helper.getBlockState(new BlockPos(1, 2, 2)).isAir(), "edge over the hole stays air");
		helper.succeed();
	}

	/** Spec §4: a hit in another colour wipes the cell and leaves only the face that was just painted. */
	@GameTest
	public void otherColourOverpaintsCellToOneFace(GameTestHelper helper) {
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE); // floor under the cell
		helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE); // wall north of the cell
		BlockPos cell = new BlockPos(2, 2, 2);
		helper.setBlock(cell, PaintBlocks.splat(PaintColor.DATA).defaultBlockState()
				.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
				.setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true));
		boolean painted = Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)), Direction.UP, PaintColor.IT);
		helper.assertTrue(painted, "the cell counts as newly painted");
		BlockState after = helper.getBlockState(cell);
		helper.assertTrue(isPaint(after, PaintColor.IT), Component.literal("cell is IT now, got " + after));
		helper.assertTrue(hasFace(after, PaintColor.IT, Direction.DOWN), "the painted face is the down one");
		helper.assertValueEqual(faces(after), 1, "face count");
		helper.assertTrue(after.getBlock() instanceof ConnectedPaintBlock, "one face is a connected cell");
		helper.succeed();
	}

	/**
	 * The tally counts faces per colour from the cells it tracks, and reset removes them. Every count here
	 * is a delta against a snapshot taken first: {@code count} folds in the whole level's display quads,
	 * and game tests in the same level run side by side, so the absolute figures are not this test's to
	 * predict.
	 */
	@GameTest
	public void tallyCountsFacesAndResets(GameTestHelper helper) {
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
		helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
		helper.setBlock(new BlockPos(4, 1, 4), Blocks.STONE);
		BlockPos dataCell = new BlockPos(2, 2, 2);
		BlockPos itCell = new BlockPos(4, 2, 4);
		helper.setBlock(dataCell, PaintBlocks.splat(PaintColor.DATA).defaultBlockState()
				.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true)
				.setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true));
		helper.setBlock(itCell, PaintBlocks.splat(PaintColor.IT).defaultBlockState()
				.setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
		// count() folds in the level's display quads, which belong to whatever else is running: take the
		// baseline first and read every figure below as this test's own contribution on top of it.
		Map<PaintColor, Integer> quads = PaintDisplays.of(helper.getLevel()).count(helper.getLevel());
		PaintTally tally = new PaintTally();
		tally.track(helper.absolutePos(dataCell));
		tally.track(helper.absolutePos(itCell));
		tally.track(helper.absolutePos(new BlockPos(0, 5, 0))); // air: must be dropped, not counted
		Map<PaintColor, Integer> counts = tally.count(helper.getLevel());
		Map<PaintColor, Integer> mine = new EnumMap<>(PaintColor.class);
		for (PaintColor color : PaintColor.values()) mine.put(color, counts.get(color) - quads.get(color));
		helper.assertValueEqual(mine.get(PaintColor.DATA), 2, "DATA faces");
		helper.assertValueEqual(mine.get(PaintColor.IT), 1, "IT faces");
		helper.assertValueEqual(tally.cells(), 2, "the air cell was dropped");
		// Two faces to one out of three painted faces in all: a third of them are IT's.
		helper.assertTrue(Math.abs(PaintTally.share(mine, PaintColor.IT) - 1f / 3f) < 1e-6, "IT share is a third");
		helper.assertTrue(Math.abs(PaintTally.share(mine, PaintColor.DATA) - 2f / 3f) < 1e-6, "DATA share is two thirds");
		int quadsBefore = PaintDisplays.of(helper.getLevel()).holders(); // reset clears the level's quads too
		int removed = tally.reset(helper.getLevel());
		helper.assertValueEqual(removed - quadsBefore, 2, "reset removed both cells");
		helper.assertTrue(helper.getBlockState(dataCell).isAir() && helper.getBlockState(itCell).isAir(), "cells are air after reset");
		helper.assertValueEqual(tally.count(helper.getLevel()).get(PaintColor.DATA), 0, "nothing left to count");
		helper.assertValueEqual(tally.cells(), 0, "and no cells left to count it from");
		helper.succeed();
	}

	private static PlayerTeam team(GameTestHelper helper, PaintColor color) {
		ServerScoreboard board = helper.getLevel().getScoreboard();
		PlayerTeam team = board.getPlayerTeam(color.id);
		return team != null ? team : board.addPlayerTeam(color.id);
	}

	/**
	 * Mock players, one scoreboard identity each.
	 *
	 * <p>Vanilla's {@code makeMockPlayer} and {@code makeMockServerPlayer} both name their player
	 * {@code test-mock-player}, and a scoreboard team is keyed by that name — so every mock in the run
	 * is the same team member, and the tests in a batch tick side by side. One test putting its mock on
	 * DATA put every other test's mock on DATA too, and the test that then cleared the name took them
	 * all off again; that is how a friendly-fire assertion came to pass for the wrong reason. These
	 * build the same two anonymous subclasses vanilla does, with a profile name nothing else in the run
	 * shares, so a team joined here is joined by this test's player alone and no test has to clear up
	 * after another one.
	 *
	 * <p>The counter is per JVM and the tag per class-load, because the game-test world — scoreboard and
	 * all — is saved and reused between runs: a bare counter would hand out {@code mock-1} again next
	 * run and find it still on a team.
	 */
	private static final AtomicInteger MOCKS = new AtomicInteger();
	private static final String MOCK_TAG = Integer.toHexString((int) (System.nanoTime() & 0xFFFFFF));

	private static GameProfile mockProfile() {
		return new GameProfile(UUID.randomUUID(), "mock-" + MOCK_TAG + "-" + MOCKS.incrementAndGet());
	}

	/** Vanilla's {@code makeMockPlayer}, with a name of its own. Not added to the level; callers do that. */
	private static Player mockPlayer(GameTestHelper helper, GameType mode) {
		return new Player(helper.getLevel(), mockProfile()) {
			@Override
			public GameType gameMode() {
				return mode;
			}

			@Override
			public boolean isClientAuthoritative() {
				return false;
			}
		};
	}

	/** Vanilla's {@code makeMockServerPlayer}, with a name of its own: a ServerPlayer, but no connection. */
	private static ServerPlayer mockServerPlayer(GameTestHelper helper, GameType mode) {
		ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), mockProfile(),
				ClientInformation.createDefault()) {
			@Override
			public GameType gameMode() {
				return mode;
			}

			@Override
			public boolean isClientAuthoritative() {
				return false;
			}
		};
		mode.updatePlayerAbilities(player.getAbilities());
		return player;
	}

	/**
	 * A mock survival <em>server</em> player on {@code color}'s team, for the wall climb: only a
	 * {@link ServerPlayer} carries the client input {@code PlayerTick} reads to decide which wall is
	 * being pushed into, so a plain mock player can never climb.
	 *
	 * <p>This one has no connection (vanilla's helper builds it without one), so the invisibility
	 * {@code PlayerTick} keeps on a squid would NPE on its way out to the client. Seeding the effect
	 * straight into the active map, well above the running-low threshold, means {@code keep} finds it
	 * healthy and never re-adds it — the climb is what these tests are about.
	 */
	private static ServerPlayer wallSquid(GameTestHelper helper, PaintColor color) {
		ServerPlayer player = mockServerPlayer(helper, GameType.SURVIVAL);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, color));
		player.getActiveEffectsMap().put(MobEffects.INVISIBILITY,
				new MobEffectInstance(MobEffects.INVISIBILITY, 600, 0, true, false, false));
		return player;
	}

	/** Pressing forward, sneaking: the input a climbing squid sends. */
	private static final Input PUSHING = new Input(true, false, false, false, false, true, false);

	/** A mock survival player holding a gun, standing at relative (4, 3, 4), on no team. */
	private static Player gunner(GameTestHelper helper) {
		// A plain mock player, not a mock ServerPlayer: that one has no connection, so vanilla's
		// ServerItemCooldowns throws when the gun starts its cooldown.
		Player player = mockPlayer(helper, GameType.SURVIVAL);
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
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		InteractionResult result = PaintWeapon.of(Weapon.SHOOTER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result.consumesAction(), "use succeeds on a team");
		List<PaintBall> balls = helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0);
		helper.assertValueEqual(balls.size(), 1, "one paint ball");
		PaintBall ball = balls.getFirst();
		helper.assertTrue(ball.color() == PaintColor.DATA, "ball is DATA");
		ItemStack shown = ball.getItem();
		helper.assertTrue(shown.is(Items.FIREWORK_STAR), Component.literal("ball shows a firework star, got " + shown));
		FireworkExplosion explosion = shown.get(DataComponents.FIREWORK_EXPLOSION);
		helper.assertTrue(explosion != null && explosion.colors().contains(PaintColor.DATA.rgb), "star is tinted DATA");
		helper.assertTrue(player.getCooldowns().isOnCooldown(player.getItemInHand(InteractionHand.MAIN_HAND)), "cooldown started");
		ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
		PaintWeapon.of(Weapon.SHOOTER).inventoryTick(held, helper.getLevel(), player, EquipmentSlot.MAINHAND);
		DyedItemColor dye = held.get(DataComponents.DYED_COLOR);
		helper.assertTrue(dye != null && dye.rgb() == PaintColor.DATA.rgb, "the held gun's tank is dyed DATA");
		balls.forEach(Entity::discard);
		helper.succeed();
	}

	/** The gun stack carries the team colour as a dye, nothing without a team, and loses a stale dye. */
	@GameTest
	public void gunTankTakesTeamColour(GameTestHelper helper) {
		ItemStack onTeam = PaintWeapon.withTankColor(new ItemStack(Items.WARPED_FUNGUS_ON_A_STICK), team(helper, PaintColor.DATA));
		DyedItemColor dye = onTeam.get(DataComponents.DYED_COLOR);
		helper.assertTrue(dye != null && dye.rgb() == PaintColor.DATA.rgb, "tank dyed DATA");
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
		PaintBall ball = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.DATA);
		Vec3 from = helper.absoluteVec(new Vec3(2.5, 4, 2.5));
		ball.setPos(from.x, from.y, from.z);
		ball.setDeltaMovement(0, -0.6, 0); // straight down onto the floor block at relative (2, 1, 2)
		helper.getLevel().addFreshEntity(ball);
		helper.runAfterDelay(10, () -> {
			BlockPos cell = new BlockPos(2, 2, 2);
			BlockState state = helper.getBlockState(cell);
			helper.assertTrue(isPaint(state, PaintColor.DATA),
					Component.literal("the cell where the ball landed should be DATA paint, got " + state));
			helper.assertTrue(hasFace(state, PaintColor.DATA, Direction.DOWN), "paint sits on its down face");
			// A default ball carries the shooter's bounces, so ten ticks on it is still in the air on its
			// way back up from the floor it just painted (along with the droplets that bounce threw off);
			// what the hit must have spent is one of those bounces.
			List<PaintBall> left = helper.getEntities(PaintBall.TYPE, cell, 4.0);
			helper.assertTrue(left.stream().allMatch(other -> other.isDroplet() || other.bouncesLeft() < Weapon.SHOOTER_BOUNCES),
					"the ball is gone after the hit, or has spent a bounce");
			// A bouncing ball outlives the test it was thrown in; left alone it would sail on and paint
			// into whatever test structure sits next door.
			left.forEach(Entity::discard);
			helper.succeed();
		});
	}

	/**
	 * A ball that hits a teammate paints the floor under them and leaves their health alone.
	 *
	 * <p>Two halves, and they are deliberately not in the same tick. Friendly fire is checked
	 * <em>synchronously</em>, against a direct {@code onHitEntity}, because a health assertion ten ticks
	 * out is an assertion about whatever else has happened to this player since. The flight is then
	 * checked for what only a flight can show: that a ball thrown at a player lands on them and paints
	 * the cell under their feet. The synchronous half paints that cell too, so it is wiped first and
	 * asserted empty; the flight has to put the paint there itself.
	 */
	@GameTest
	public void paintBallOnEntityPaintsUnderneathWithoutDamage(GameTestHelper helper) {
		stoneFloor(helper, 5);
		Player shooter = gunner(helper); // a different mock player: a projectile never hits its own owner
		Player target = mockPlayer(helper, GameType.SURVIVAL);
		Vec3 stand = helper.absoluteVec(new Vec3(2.5, 2, 2.5));
		target.setPos(stand.x, stand.y, stand.z);
		// The ball's own colour is what decides friendly fire, so this team is the whole setup.
		helper.getLevel().getScoreboard().addPlayerToTeam(target.getScoreboardName(), team(helper, PaintColor.DATA));
		// The projectile's entity sweep only sees entities the level knows about.
		helper.assertTrue(helper.getLevel().addFreshEntity(target), "the target player joined the level");
		target.setHealth(target.getMaxHealth());
		target.damageCooldownTime = 0;
		float health = target.getHealth();
		helper.assertFalse(PaintBall.hostile(PaintColor.DATA, target), "a teammate is not a target");
		PaintBall direct = new PaintBall(helper.getLevel(), shooter, PaintColor.DATA);
		direct.setPos(stand.x, stand.y + 1.0, stand.z);
		helper.assertTrue(direct.damage() > 0, "the ball would hurt someone, damage " + direct.damage());
		direct.onHitEntity(new EntityHitResult(target));
		helper.assertValueEqual(target.getHealth(), health, "a teammate takes no damage from a direct hit");
		direct.discard();
		// Wipe what the direct hit painted, so the flight below has to paint the cell from scratch.
		helper.setBlock(new BlockPos(2, 2, 2), Blocks.AIR);
		helper.assertFalse(isPaint(helper.getBlockState(new BlockPos(2, 2, 2)), PaintColor.DATA), "the cell starts empty");
		PaintBall ball = new PaintBall(helper.getLevel(), shooter, PaintColor.DATA);
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
			helper.assertTrue(isPaint(state, PaintColor.DATA),
					Component.literal("the floor under the player should be DATA paint, got " + state));
			helper.assertTrue(hasFace(state, PaintColor.DATA, Direction.DOWN), "paint sits on its down face");
			target.discard();
			helper.getLevel().getScoreboard().removePlayerFromTeam(target.getScoreboardName());
			helper.succeed();
		});
	}

	/** A splash on the floor beside a wall paints the wall's face too (the rays), not only the floor. */
	/**
	 * A ball that lands on someone from the other team takes hearts off them, and still paints the floor
	 * under their feet. {@code onHitEntity} is called directly: putting a ball in flight and waiting for
	 * it to arrive is a different test's job.
	 */
	@GameTest
	public void directHitHurtsTheOtherTeam(GameTestHelper helper) {
		stoneFloor(helper, 5);
		Player target = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(target.getScoreboardName(), team(helper, PaintColor.IT));
		Vec3 at = helper.absoluteVec(new Vec3(2.5, 2.0, 2.5));
		target.setPos(at.x, at.y, at.z);
		target.setHealth(target.getMaxHealth());
		target.damageCooldownTime = 0;
		float before = target.getHealth();
		PaintBall ball = new PaintBall(helper.getLevel(), null, PaintColor.DATA, 0, 0);
		ball.setPos(at.x, at.y + 1.0, at.z);
		helper.assertValueEqual(ball.damage(), Weapon.SHOOTER.damage, "a ball carries the shooter's damage by default");
		ball.onHitEntity(new EntityHitResult(target));
		helper.assertValueEqual(target.getHealth(), before - Weapon.SHOOTER.damage, "the hit took the shooter's damage off");
		helper.assertTrue(isPaint(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(2, 2, 2))), PaintColor.DATA),
				"and the floor under them is still painted");
		// No team at all is fair game: an arena full of untagged mobs must not be cover.
		helper.getLevel().getScoreboard().removePlayerFromTeam(target.getScoreboardName());
		helper.assertTrue(PaintBall.hostile(PaintColor.DATA, target), "someone on no team can still be shot");
		helper.succeed();
	}

	/** The same ball against one of your own: paint under their feet, not a scratch on them. */
	@GameTest
	public void directHitSparesTheOwnTeam(GameTestHelper helper) {
		stoneFloor(helper, 5);
		Player target = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(target.getScoreboardName(), team(helper, PaintColor.DATA));
		Vec3 at = helper.absoluteVec(new Vec3(2.5, 2.0, 2.5));
		target.setPos(at.x, at.y, at.z);
		target.setHealth(target.getMaxHealth());
		target.damageCooldownTime = 0;
		float before = target.getHealth();
		PaintBall ball = new PaintBall(helper.getLevel(), null, PaintColor.DATA, 0, 0);
		ball.setPos(at.x, at.y + 1.0, at.z);
		ball.onHitEntity(new EntityHitResult(target));
		helper.assertValueEqual(target.getHealth(), before, "a teammate takes no damage");
		helper.assertFalse(PaintBall.hostile(PaintColor.DATA, target), "and is not a target at all");
		helper.assertTrue(PaintBall.hostile(PaintColor.IT, target), "though the other colour may shoot them");
		helper.assertTrue(isPaint(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(2, 2, 2))), PaintColor.DATA),
				"the paint lands on a teammate all the same");
		helper.getLevel().getScoreboard().removePlayerFromTeam(target.getScoreboardName());
		helper.succeed();
	}

	@GameTest
	public void splashPaintsAdjacentWall(GameTestHelper helper) {
		stoneFloor(helper, 5);
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(4, y, 2), Blocks.STONE); // wall east of the hit
		BlockPos struck = new BlockPos(3, 1, 2);
		Vec3 impact = helper.absoluteVec(new Vec3(3.6, 2.0, 2.5));
		int changed = Painter.splash(helper.getLevel(), impact, helper.absolutePos(struck), Direction.UP, PaintColor.DATA,
				helper.getLevel().getRandom(), null);
		helper.assertTrue(changed >= 5, "blob plus rays painted at least five cells, got " + changed);
		BlockState floorCell = helper.getBlockState(new BlockPos(3, 2, 2));
		helper.assertTrue(hasFace(floorCell, PaintColor.DATA, Direction.DOWN), "floor cell painted");
		BlockState wallCell = helper.getBlockState(new BlockPos(3, 2, 2)); // same cell holds the wall's west face
		helper.assertTrue(hasFace(wallCell, PaintColor.DATA, Direction.EAST),
				Component.literal("the wall face east of the hit is painted, got " + wallCell));
		helper.succeed();
	}

	/** Setup creates one vanilla team per colour with the matching colour, no friendly fire, no collisions. */
	/** Every ovvar ovve id belongs to a chapter; nothing else in or out of the namespace does. */
	@GameTest
	public void ovveIdsMapToTeams(GameTestHelper helper) {
		for (String id : new String[] {"data_ovve", "data_ovve_top", "data_polymiter_ovve"}) {
			helper.assertValueEqual(OvveTeams.colourOf(Identifier.fromNamespaceAndPath("ovvar", id)),
					Optional.of(PaintColor.DATA), id + " is a DATA ovve");
		}
		for (String id : new String[] {"it_ovve", "it_kisel_ovve", "it_polymiter_ovve"}) {
			helper.assertValueEqual(OvveTeams.colourOf(Identifier.fromNamespaceAndPath("ovvar", id)),
					Optional.of(PaintColor.IT), id + " is an IT ovve");
		}
		helper.assertValueEqual(OvveTeams.colourOf(Identifier.fromNamespaceAndPath("ovvar", "media_frack")),
				Optional.empty(), "the media frack belongs to no chapter");
		helper.assertValueEqual(OvveTeams.colourOf(Identifier.fromNamespaceAndPath("minecraft", "leather_leggings")),
				Optional.empty(), "and nothing outside the namespace is an ovve");
		// The stack path over a real registered item, since no ovvar item is on the test classpath.
		helper.assertValueEqual(OvveTeams.colourOf(new ItemStack(Items.LEATHER_LEGGINGS)), Optional.empty(), "vanilla trousers are not an ovve");
		helper.assertValueEqual(OvveTeams.colourOf(ItemStack.EMPTY), Optional.empty(), "bare legs are not an ovve");
		helper.succeed();
	}

	/**
	 * A player wearing no ovve keeps the team they were put on by hand — the check must never strip
	 * someone for changing trousers. (The joining half needs an ovvar item, which is not on the test
	 * classpath; {@link OvveTeams#colourOf} is what decides it and is covered above.)
	 */
	@GameTest
	public void wornOvveJoinsTheTeam(GameTestHelper helper) {
		Player player = gunner(helper);
		ServerScoreboard board = helper.getLevel().getScoreboard();
		board.addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.IT));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
		helper.assertValueEqual(OvveTeams.worn(player), Optional.empty(), "leather trousers dress you as nobody");
		PlayerTick.tick(player, 20); // an ovve tick
		helper.assertValueEqual(PaintColor.byTeam(player.getTeam()), Optional.of(PaintColor.IT), "the team they were put on by hand stands");
		board.removePlayerFromTeam(player.getScoreboardName());
		helper.succeed();
	}

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
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		int before = Recoil.pending();
		InteractionResult result = PaintWeapon.of(Weapon.SHOOTER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result.consumesAction(), "shot succeeds");
		helper.assertValueEqual(Recoil.pending(), before, "no settle queued for a connectionless player");
		helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0).forEach(Entity::discard);
		helper.succeed();
	}

	/**
	 * A stair top takes paint as display quads: tracked, counted in the colour, dropped when the surface
	 * goes (waterlogged stairs included), removed by reset. Holder counts are deltas against a snapshot
	 * taken first, since {@link PaintDisplays} is per level and other tests in the same level hold quads
	 * of their own; the two figures that are absolute are the post-conditions of a level-wide clear,
	 * which is what {@code reset} is.
	 */
	@GameTest
	public void stairTakesDisplayPaint(GameTestHelper helper) {
		BlockPos stair = new BlockPos(2, 1, 2);
		helper.setBlock(stair, Blocks.STONE_STAIRS.defaultBlockState());
		PaintDisplays displays = PaintDisplays.of(helper.getLevel());
		int before = displays.holders();
		boolean painted = Painter.paintFace(helper.getLevel(), helper.absolutePos(stair), Direction.UP, PaintColor.DATA);
		helper.assertTrue(painted, "stair top accepted paint");
		helper.assertTrue(helper.getBlockState(stair.above()).isAir(), "no paint block above a stair (quads instead)");
		helper.assertValueEqual(displays.holders(), before + 1, "one holder for the cell");
		helper.assertTrue(displays.colorAt(helper.absolutePos(stair.above())) == PaintColor.DATA, "cell is DATA");
		// The quads are block displays of the paint's own client state, one per outline box on the face.
		List<BlockState> quads = displays.statesAt(helper.absolutePos(stair.above()));
		helper.assertTrue(!quads.isEmpty() && quads.size() <= 3, "one to three quads, got " + quads.size());
		for (BlockState quad : quads) {
			helper.assertValueEqual(quad, PaintStates.connected(PaintColor.DATA, Direction.DOWN, 0), "the DATA floor state");
		}
		helper.assertTrue(displays.count(helper.getLevel()).get(PaintColor.DATA) >= 1, "counted as DATA faces");
		boolean recoloured = Painter.paintFace(helper.getLevel(), helper.absolutePos(stair), Direction.UP, PaintColor.IT);
		helper.assertTrue(recoloured && displays.colorAt(helper.absolutePos(stair.above())) == PaintColor.IT, "recoloured to IT");
		helper.assertValueEqual(displays.holders(), before + 1, "recolour reuses the cell");
		BlockPos wet = new BlockPos(5, 1, 5);
		helper.setBlock(wet, Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(wet), Direction.UP, PaintColor.DATA),
				"a waterlogged stair still takes paint");
		// The quads die with their surface. A chunk unload does the same thing by another route — Polymer
		// destroys the holder's attachment — but a game test cannot unload its own chunks, so this half of
		// the rule stands in for both.
		helper.setBlock(stair, Blocks.AIR.defaultBlockState());
		displays.count(helper.getLevel()); // the sweep that prunes cells whose paint is gone
		helper.assertTrue(displays.colorAt(helper.absolutePos(stair.above())) == null, "the broken stair took its cell with it");
		helper.assertValueEqual(displays.holders(), before + 1, "only the waterlogged stair's holder is left");
		helper.setBlock(stair, Blocks.STONE_STAIRS.defaultBlockState());
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(stair), Direction.UP, PaintColor.IT),
				"a rebuilt stair takes the same colour again");
		PaintTally tally = new PaintTally();
		helper.assertTrue(tally.count(helper.getLevel()).get(PaintColor.IT) >= 1, "the tally counts the quads as IT faces");
		int removed = tally.reset(helper.getLevel()); // a reset clears the level's display quads too
		helper.assertTrue(removed >= 1 && displays.holders() == 0, "clear removed the quads");
		helper.assertValueEqual(tally.count(helper.getLevel()).get(PaintColor.IT), 0, "nothing left to count");
		// A surface that only changes shape keeps its position, so every check above still passes, but the
		// quads were cut to the old shape and now hang over nothing: the cell must be dropped.
		BlockPos turned = new BlockPos(6, 1, 6);
		helper.setBlock(turned, Blocks.STONE_STAIRS.defaultBlockState()); // default facing is north
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(turned), Direction.UP, PaintColor.DATA),
				"the stair took paint");
		helper.assertTrue(displays.colorAt(helper.absolutePos(turned.above())) == PaintColor.DATA, "turned stair's cell is DATA");
		helper.setBlock(turned, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
		displays.count(helper.getLevel()); // the sweep
		helper.assertTrue(displays.colorAt(helper.absolutePos(turned.above())) == null,
				"turning the stair under the quads dropped the cell");
		helper.assertValueEqual(displays.holders(), 0, "no holders left");
		helper.succeed();
	}

	/**
	 * A display quad is the paint state itself: a block display showing
	 * {@link PaintStates#connected} for the cell's colour, its attach direction (the opposite of the face
	 * it was painted on — floor paint on a slab top attaches DOWN, exactly as a chunk cell does) and its
	 * connection bits. Every quad in a cell shows the same state, and the state is one of the table's own,
	 * so the pack already has a model and a texture for it and the item shader's gloss finds the marker
	 * alpha in it.
	 */
	@GameTest
	public void displayQuadsWearRealPaintStates(GameTestHelper helper) {
		BlockPos slab = new BlockPos(2, 1, 2);
		helper.setBlock(slab, Blocks.STONE_SLAB.defaultBlockState());
		PaintDisplays displays = PaintDisplays.of(helper.getLevel());
		BlockPos cell = helper.absolutePos(slab.above());
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(slab), Direction.UP, PaintColor.DATA),
				"slab top accepted paint");
		List<BlockState> states = displays.statesAt(cell);
		helper.assertTrue(!states.isEmpty(), "the cell holds quads");
		helper.assertValueEqual(displays.bitsAt(cell), 0, "an isolated quad has no connections");
		for (BlockState state : states) {
			helper.assertTrue(PaintStates.all().contains(state), "a real client paint state: " + state);
			PaintStates.Entry entry = PaintStates.entry(state);
			helper.assertTrue(entry.color() == PaintColor.DATA, "DATA, got " + entry.color());
			helper.assertTrue(entry.face() == Direction.DOWN, "attaches DOWN under the paint's own face, got " + entry.face());
			helper.assertValueEqual(entry.bits(), 0, "no bits");
			helper.assertValueEqual(state, PaintStates.connected(PaintColor.DATA, Direction.DOWN, 0), "the table's state for it");
		}
		helper.succeed();
	}

	/**
	 * Quads border like blocks. Two slab tops side by side each gain the bit pointing at the other, and a
	 * paint block painted into the cell beside a quad opens that quad's border towards it — the quads are
	 * not blocks, so nothing tells them about a new neighbour but {@link Painter} itself. The reverse does
	 * not hold: a paint block's own bits come from {@link ConnectedPaintBlock#neighbourBits}, which reads
	 * block states, so it leaves its edge closed against a quad.
	 */
	@GameTest
	public void displayQuadsConnectToNeighbours(GameTestHelper helper) {
		PaintDisplays displays = PaintDisplays.of(helper.getLevel());
		BlockPos west = new BlockPos(2, 1, 4), east = new BlockPos(3, 1, 4);
		helper.setBlock(west, Blocks.STONE_SLAB.defaultBlockState());
		helper.setBlock(east, Blocks.STONE_SLAB.defaultBlockState());
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(west), Direction.UP, PaintColor.DATA), "west slab painted");
		helper.assertValueEqual(displays.bitsAt(helper.absolutePos(west.above())), 0, "alone so far");
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(east), Direction.UP, PaintColor.DATA), "east slab painted");
		// inPlane for a DOWN attach is {WEST, EAST, NORTH, SOUTH}: bit 1 is +u (east), bit 0 is -u (west).
		helper.assertValueEqual(displays.bitsAt(helper.absolutePos(west.above())), 2, "the west quad reaches east");
		helper.assertValueEqual(displays.bitsAt(helper.absolutePos(east.above())), 1, "the east quad reaches west");
		for (BlockState state : displays.statesAt(helper.absolutePos(east.above()))) {
			helper.assertValueEqual(state, PaintStates.connected(PaintColor.DATA, Direction.DOWN, 1), "and shows the bordered state");
		}
		// A full block north of the east slab, painted on top: its paint block lands in the cell in-plane
		// with the quad, which is bit 2 (-v, north) for a DOWN attach.
		BlockPos north = new BlockPos(3, 1, 3);
		helper.setBlock(north, Blocks.STONE);
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(north), Direction.UP, PaintColor.DATA), "floor block painted");
		helper.assertTrue(Painter.isPaint(helper.getBlockState(north.above())), "a paint block, not quads");
		helper.assertValueEqual(displays.bitsAt(helper.absolutePos(east.above())), 1 | 4, "the quad borders the paint block too");
		helper.assertValueEqual(ConnectedPaintBlock.bits(helper.getBlockState(north.above())) & 8, 0,
				"and the paint block leaves its own edge closed against the quad");
		helper.succeed();
	}

	/** The gloss lives in the terrain shader pair (what actually draws chunks in 26.3), keyed on the paint alpha marker. */
	@GameTest
	public void glossShaderCarriesTheMarkerGuard(GameTestHelper helper) {
		String fsh = new String(RivalsPack.shader("terrain.fsh"), StandardCharsets.UTF_8);
		String vsh = new String(RivalsPack.shader("terrain.vsh"), StandardCharsets.UTF_8);
		helper.assertTrue(fsh.contains("RIVALS_GLOSS") && fsh.contains("0.9216") && fsh.contains("0.008"), "fragment shader guards on the marker alpha");
		helper.assertTrue(fsh.contains("sampleRGSS") && fsh.contains("#ifdef ALPHA_CUTOUT"), "vanilla terrain sampling and cutout kept");
		helper.assertTrue(vsh.contains("out vec3 viewPos") && vsh.contains("ChunkPosition"), "vertex shader exports the view position from the chunk-relative position");
		// The in-plane cell coordinate the border is cut from: the pair has to agree or the paint is untextured.
		helper.assertTrue(vsh.contains("out vec3 chunkPos"), "vertex shader exports the chunk-relative position");
		helper.assertTrue(fsh.contains("in vec3 chunkPos"), "fragment shader reads the chunk-relative position");
		// Pixel art: the border, the wobble and the highlights are all read off texel centres.
		helper.assertTrue(fsh.contains("TEXELS") && fsh.contains("floor(p * TEXELS) + 0.5") && fsh.contains("floor(chunkPos * TEXELS) + 0.5"),
				"the paint snaps to the 16-px grid before it decides anything");
		helper.assertTrue(RivalsPack.class.getResource("/rivals_shaders/block.fsh") == null, "the block shader override is gone");
		helper.succeed();
	}

	/**
	 * The same gloss in the item pair, which is what draws the block displays on stairs, slabs and panes
	 * (cutoutBlockItemSheet → ITEM_CUTOUT → core/item). The delta over vanilla is two varyings: the view
	 * position, for the normal and the view vector, and the world position, so a quad's border is cut on
	 * the same 1/16-block grid as the paint block beside it — vanilla's item pair carries neither, and
	 * {@code Position} there is camera-relative render space, not model space, so the world position has
	 * to be rebuilt from the Globals camera.
	 */
	@GameTest
	public void itemShaderCarriesTheSameGloss(GameTestHelper helper) {
		helper.assertTrue(RivalsPack.SHADERS.containsAll(List.of("item.vsh", "item.fsh")), "the pack ships the item pair");
		String fsh = new String(RivalsPack.shader("item.fsh"), StandardCharsets.UTF_8);
		String vsh = new String(RivalsPack.shader("item.vsh"), StandardCharsets.UTF_8);
		helper.assertTrue(fsh.contains("RIVALS_GLOSS") && fsh.contains("0.9216") && fsh.contains("0.008"),
				"fragment shader guards on the marker alpha");
		helper.assertTrue(fsh.contains("in vec3 paintPos") && vsh.contains("out vec3 paintPos"), "the pair agrees on the world position");
		helper.assertTrue(fsh.contains("in vec3 viewPos") && vsh.contains("out vec3 viewPos"), "and on the view position");
		helper.assertTrue(vsh.contains("CameraBlockPos") && vsh.contains("CameraOffset"), "the world position comes off the camera");
		helper.assertTrue(fsh.contains("floor(p * TEXELS) + 0.5") && fsh.contains("floor(paintPos * TEXELS) + 0.5"),
				"and snaps to the same 16-px grid the terrain gloss does");
		// Vanilla's own item work has to survive: the cutout, the lightmap and overlay, the glint.
		helper.assertTrue(fsh.contains("#ifdef ALPHA_CUTOUT") && fsh.contains("lightMapColor") && fsh.contains("GlintSampler"),
				"vanilla item shading kept");
		helper.assertTrue(vsh.contains("minecraft_mix_light(Light0_Direction"), "vanilla item lighting kept");
		helper.succeed();
	}

	/**
	 * The ink meter: a hit from an enemy weapon throws {@link InkOnScreen#PER_DAMAGE} per point of damage
	 * onto the screen in the shooter's colour, standing in enemy paint tops it up faster than it runs off,
	 * and it clears itself once nothing is adding to it. A player off the teams has no meter at all.
	 */
	@GameTest
	public void inkOnScreenRisesOnHitAndDecays(GameTestHelper helper) {
		ServerPlayer player = mockServerPlayer(helper, GameType.SURVIVAL);
		PlayerTeam data = team(helper, PaintColor.DATA);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), data);
		InkOnScreen.forget(player);
		helper.assertValueEqual(InkOnScreen.amount(player), 0, "a clean screen to start");
		InkOnScreen.hit(player, PaintColor.IT, 4.0f);
		helper.assertValueEqual(InkOnScreen.amount(player), 4 * InkOnScreen.PER_DAMAGE, "four damage of IT ink");
		helper.assertTrue(InkOnScreen.color(player) == PaintColor.IT, "in the shooter's colour");
		// Ink from the other team repaints the visor rather than mixing: one colour is all the shader draws.
		InkOnScreen.hit(player, PaintColor.DATA, 1.0f);
		helper.assertValueEqual(InkOnScreen.amount(player), 5 * InkOnScreen.PER_DAMAGE, "and one more point on top");
		helper.assertTrue(InkOnScreen.color(player) == PaintColor.DATA, "the newest ink is the ink you see");
		int full = InkOnScreen.amount(player);
		InkOnScreen.tick(player, 100);
		helper.assertValueEqual(InkOnScreen.amount(player), full - InkOnScreen.DECAY, "it runs off every tick");
		// A tick spent standing in it adds less than the decay takes, so wading only slows the clearing.
		int standing = InkOnScreen.amount(player);
		InkOnScreen.standing(player, PaintColor.IT);
		InkOnScreen.tick(player, 102);
		helper.assertValueEqual(InkOnScreen.amount(player), standing + InkOnScreen.STANDING_GAIN - InkOnScreen.DECAY,
				"standing in enemy ink tops it up as it drains");
		InkOnScreen.hit(player, PaintColor.IT, 100.0f);
		helper.assertValueEqual(InkOnScreen.amount(player), InkOnScreen.MAX, "never more than the amount byte holds");
		for (int i = 0; i < InkOnScreen.MAX / InkOnScreen.DECAY + 2; i++) InkOnScreen.tick(player, 200 + i * 2L);
		helper.assertValueEqual(InkOnScreen.amount(player), 0, "and it clears itself with nothing adding to it");
		helper.assertTrue(InkOnScreen.color(player) == null, "with no colour left behind");
		// Not in a match: the meter goes, and with it the title the shader reads.
		InkOnScreen.hit(player, PaintColor.IT, 2.0f);
		helper.getLevel().getScoreboard().removePlayerFromTeam(player.getScoreboardName(), data);
		InkOnScreen.tick(player, 400);
		helper.assertValueEqual(InkOnScreen.amount(player), 0, "no team, no ink on the screen");
		// A hit on something that is not a player, and a hit that did nothing, are both no ink.
		InkOnScreen.hit(player, PaintColor.IT, 0.0f);
		helper.assertValueEqual(InkOnScreen.amount(player), 0, "a hit for no damage leaves no ink");
		helper.succeed();
	}

	/**
	 * The data pixel: one glyph of the mod's own font, coloured (255, team, amount). Red at full and green
	 * under 16 is the signature the probe shader hunts for; green's low nibble is the enemy team, so the
	 * shader knows which ink to draw; blue is the amount. Nothing here may drift from the shader's own
	 * decode without the ink coming out the wrong colour or the wrong size.
	 */
	@GameTest
	public void inkTitleEncodesAmountAndColour(GameTestHelper helper) {
		Component title = InkOnScreen.title(PaintColor.IT, 200);
		helper.assertValueEqual(title.getString(), String.valueOf(InkOnScreen.MARKER), "one glyph and nothing else");
		TextColor color = title.getStyle().getColor();
		helper.assertTrue(color != null, "the glyph carries a colour");
		int rgb = color.getValue();
		helper.assertValueEqual(rgb >> 16 & 0xFF, InkOnScreen.SIGNATURE_RED, "red at full");
		helper.assertValueEqual(rgb >> 8 & 0xFF, PaintColor.IT.ordinal(), "green is the enemy team's index");
		helper.assertTrue((rgb >> 8 & 0xFF) < 16, "and stays inside the nibble the shader tests");
		helper.assertValueEqual(rgb & 0xFF, 200, "blue is the amount");
		helper.assertTrue(title.getStyle().getFont() instanceof FontDescription.Resource font
				&& font.id().equals(Rivals.id(InkOnScreen.FONT)), "drawn in the data font: " + title.getStyle().getFont());
		TextColor dataInk = InkOnScreen.title(PaintColor.DATA, 1).getStyle().getColor();
		helper.assertTrue(dataInk != null && dataInk.getValue() == 0xFF0001, "DATA at amount 1 is 0xFF0001");
		// Out-of-range amounts are clamped rather than spilling into the team nibble.
		TextColor over = InkOnScreen.title(PaintColor.DATA, 9000).getStyle().getColor();
		helper.assertTrue(over != null && over.getValue() == 0xFF00FF, "clamped to the byte, teams untouched");
		helper.succeed();
	}

	/**
	 * The pack side of the ink: the {@code end_of_frame} chain vanilla asks for every frame, its two
	 * shaders, and the one-glyph font the meter is written in. The chain has to read
	 * {@code minecraft:main}, find the data pixel in a one-by-one target (so the search runs once a frame
	 * rather than once a pixel) and put the frame back where it found it.
	 */
	@GameTest
	public void packCarriesTheInkPostEffect(GameTestHelper helper) throws IOException {
		Map<String, byte[]> files = InkArt.packFiles();
		String chainPath = "assets/minecraft/post_effect/end_of_frame.json";
		String fontPath = "assets/metacraft-rivals/font/data.json";
		String glyphPath = "assets/metacraft-rivals/textures/font/data.png";
		for (String path : new String[] {chainPath, "assets/metacraft-rivals/shaders/post/ink.fsh",
				"assets/metacraft-rivals/shaders/post/ink_probe.fsh", fontPath, glyphPath}) {
			helper.assertTrue(files.containsKey(path), "in pack: " + path);
		}
		JsonObject chain = JsonParser.parseString(new String(files.get(chainPath), StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject probeTarget = chain.getAsJsonObject("targets").getAsJsonObject("rivals_ink_data");
		helper.assertValueEqual(probeTarget.get("width").getAsInt(), 1, "the data target is one pixel wide");
		helper.assertValueEqual(probeTarget.get("height").getAsInt(), 1, "and one pixel tall");
		JsonArray passes = chain.getAsJsonArray("passes");
		helper.assertValueEqual(passes.size(), 3, "probe, ink, blit back");
		helper.assertValueEqual(passes.get(0).getAsJsonObject().get("fragment_shader").getAsString(),
				"metacraft-rivals:post/ink_probe", "the probe runs first");
		helper.assertValueEqual(passes.get(0).getAsJsonObject().get("output").getAsString(), "rivals_ink_data", "into the data target");
		JsonObject inkPass = passes.get(1).getAsJsonObject();
		helper.assertValueEqual(inkPass.get("fragment_shader").getAsString(), "metacraft-rivals:post/ink", "then the ink");
		List<String> samplers = new ArrayList<>();
		for (JsonElement input : inkPass.getAsJsonArray("inputs")) {
			JsonObject json = input.getAsJsonObject();
			samplers.add(json.get("sampler_name").getAsString() + "=" + json.get("target").getAsString());
		}
		helper.assertTrue(samplers.contains("In=minecraft:main") && samplers.contains("Probe=rivals_ink_data"),
				"reading the frame and the data pixel: " + samplers);
		helper.assertValueEqual(passes.get(2).getAsJsonObject().get("output").getAsString(), "minecraft:main",
				"and the frame goes back where it came from");
		// The shaders' own halves of the contract.
		String probe = new String(files.get("assets/metacraft-rivals/shaders/post/ink_probe.fsh"), StandardCharsets.UTF_8);
		helper.assertTrue(probe.contains("frame.r > 0.99") && probe.contains("frame.g < 0.0627"),
				"the probe tests the marker signature: red at full, green under 16");
		helper.assertTrue(probe.contains("ScreenSize"), "and searches in screen pixels");
		String ink = new String(files.get("assets/metacraft-rivals/shaders/post/ink.fsh"), StandardCharsets.UTF_8);
		helper.assertTrue(ink.contains("ProbeSampler") && ink.contains("GameTime"), "the ink reads the data pixel and the clock");
		for (PaintColor team : PaintColor.values()) {
			// Both team inks are hard-coded in the shader, so they have to be the colours the teams wear.
			String red = String.format(Locale.ROOT, "%.4f", (team.rgb >> 16 & 0xFF) / 255.0);
			helper.assertTrue(ink.contains(red), team + "'s ink (" + red + ") is in the shader");
		}
		// The font: one bitmap provider, our glyph, and a sprite that is solid white so the style's colour
		// is what lands on the screen.
		JsonObject font = JsonParser.parseString(new String(files.get(fontPath), StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject provider = font.getAsJsonArray("providers").get(0).getAsJsonObject();
		helper.assertValueEqual(provider.get("type").getAsString(), "bitmap", "a bitmap provider");
		helper.assertValueEqual(provider.get("file").getAsString(), "metacraft-rivals:font/data.png", "of the generated sprite");
		helper.assertValueEqual(provider.getAsJsonArray("chars").get(0).getAsString(), String.valueOf(InkOnScreen.MARKER),
				"for the character the title is made of");
		helper.assertValueEqual(provider.get("height").getAsInt(), InkArt.GLYPH_SIZE, "one text pixel per texel");
		BufferedImage glyph = ImageIO.read(new ByteArrayInputStream(files.get(glyphPath)));
		helper.assertValueEqual(glyph.getWidth(), InkArt.GLYPH_SIZE, "the sprite is as wide as the font says");
		helper.assertValueEqual(glyph.getHeight(), InkArt.GLYPH_SIZE, "and as tall");
		for (int y = 0; y < glyph.getHeight(); y++) {
			for (int x = 0; x < glyph.getWidth(); x++) {
				helper.assertValueEqual(glyph.getRGB(x, y), 0xFFFFFFFF, "opaque white at " + x + "," + y);
			}
		}
		helper.succeed();
	}

	/** A fresh gun holds 40 ink, a shot costs one, an empty gun refills after the delay, own paint tops it up. */
	@GameTest
	public void inkDrainsRefillsAndTopsUp(GameTestHelper helper) {
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
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
		String full = InkHud.bar(PaintColor.DATA, Ink.MAX, false, false).getString();
		helper.assertTrue(full.startsWith("INK ") && full.contains("40/40") && full.chars().filter(c -> c == '\u2588').count() == 10, "full bar: " + full);
		String half = InkHud.bar(PaintColor.DATA, 20, false, false).getString();
		helper.assertTrue(half.chars().filter(c -> c == '\u2588').count() == 5 && half.chars().filter(c -> c == '\u2591').count() == 5, "half bar: " + half);
		helper.assertTrue(InkHud.bar(PaintColor.DATA, 0, true, false).getString().contains("REFILLING"), "refilling text");
		helper.assertTrue(InkHud.bar(PaintColor.DATA, 5, false, true).getString().contains("SQUID"), "squid tag");
		// The charger's charge rides the same line, and only when there is one to show.
		String charging = InkHud.bar(PaintColor.DATA, 26, false, false, 0.48f).getString();
		helper.assertTrue(charging.contains("CHARGE") && charging.contains("48%"), "the charge reads out: " + charging);
		helper.assertTrue(charging.contains("▮") && charging.contains("▯"), "as a part-filled bar: " + charging);
		helper.assertTrue(!full.contains("CHARGE"), "and is left off when nothing is charging: " + full);
		Component charged = InkHud.bar(PaintColor.DATA, 26, false, false, 1.0f);
		helper.assertTrue(charged.getString().contains("100%"), "a full charge reads 100%: " + charged.getString());
		helper.assertTrue(!charged.getSiblings().isEmpty() && charged.getSiblings().getFirst().getStyle().isBold(),
				"and stands out when it is full");
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
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), Direction.UP, PaintColor.DATA);
		helper.assertTrue(PlayerTick.paintUnder(player) == PaintColor.DATA, "own paint under the player");
		player.setShiftKeyDown(true);
		PlayerTick.tick(player, 0);
		helper.assertTrue(PlayerTick.isSquid(player), "squid form on");
		helper.assertTrue(player.hasEffect(MobEffects.INVISIBILITY), "invisible");
		AttributeInstance scale = player.getAttribute(Attributes.SCALE);
		helper.assertTrue(scale != null && scale.hasModifier(SquidState.SCALE_ID) && scale.getValue() < 0.6, "squid is half size");
		helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(SquidState.SPEED_ID), "squid is fast");
		helper.assertTrue(player.getAttribute(Attributes.JUMP_STRENGTH).hasModifier(SquidState.JUMP_ID), "squid hops");
		helper.assertTrue(player.getAttribute(Attributes.STEP_HEIGHT).hasModifier(SquidState.STEP_ID), "squid glides over steps");
		helper.assertTrue(player.getAttribute(Attributes.SNEAKING_SPEED).hasModifier(SquidState.SNEAK_ID), "squid sneak penalty is lifted");
		helper.assertTrue(player.getAttribute(Attributes.SAFE_FALL_DISTANCE).hasModifier(SquidState.SAFE_FALL_ID), "squid hop lands safely");
		helper.assertTrue(player.getAttribute(Attributes.GRAVITY).hasModifier(SquidState.GRAVITY_ID), "squid arc is floatier");
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
		helper.assertTrue(!player.getAttribute(Attributes.SNEAKING_SPEED).hasModifier(SquidState.SNEAK_ID)
				&& !player.getAttribute(Attributes.SAFE_FALL_DISTANCE).hasModifier(SquidState.SAFE_FALL_ID)
				&& !player.getAttribute(Attributes.GRAVITY).hasModifier(SquidState.GRAVITY_ID), "dive modifiers removed on exit");
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), Direction.UP, PaintColor.IT);
		PlayerTick.tick(player, 2);
		helper.assertTrue(player.hasEffect(MobEffects.SLOWNESS), "enemy paint slows");
		helper.assertValueEqual(player.getEffect(MobEffects.SLOWNESS).getAmplifier(), 1, "Slowness II");
		helper.assertTrue(player.getAttribute(Attributes.JUMP_STRENGTH).hasModifier(SquidState.NO_JUMP_ID), "enemy ink kills the jump");
		helper.succeed();
	}

	/** Entering squid form from a stand is a dive: a horizontal shove along the look direction. */
	@GameTest
	public void squidDiveSurges(GameTestHelper helper) {
		helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE);
		Player player = gunner(helper); // stands at relative (4, 3, 4), i.e. in the cell above that stone
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), Direction.UP, PaintColor.DATA);
		player.setYRot(-90f); // look +X
		player.setXRot(0f);
		helper.assertTrue(!player.isShiftKeyDown(), "not sneaking yet");
		PlayerTick.tick(player, 0); // standing in own paint, not shift: no squid form, no surge
		helper.assertTrue(!PlayerTick.isSquid(player), "not squid before shifting");
		helper.assertTrue(player.getDeltaMovement().horizontalDistance() < 0.01, "no surge before the dive");
		player.setShiftKeyDown(true);
		PlayerTick.tick(player, 1);
		helper.assertTrue(PlayerTick.isSquid(player), "squid form on after diving");
		Vec3 delta = player.getDeltaMovement();
		helper.assertTrue(delta.horizontalDistance() >= 0.3, "dive surge pushes horizontally, got " + delta);
		helper.assertTrue(delta.x > 0, "surge follows the look direction (+X), got " + delta);
		helper.succeed();
	}

	/**
	 * Others see a squid, not a floating nothing: a team-coloured blob rides the player's feet while the
	 * form is on, and goes down with it. The blob's own player is never sent it, which is a per-viewer
	 * thing a server-side test cannot see; what it can see is that the holder exists, carries one
	 * element, is attached, and is destroyed on the way out.
	 */
	@GameTest
	public void squidShowsABlobToOthers(GameTestHelper helper) {
		helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE);
		Player player = gunner(helper); // stands at relative (4, 3, 4), the cell above that stone
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), Direction.UP, PaintColor.DATA);
		helper.getLevel().addFreshEntity(player); // a display rides an entity the level knows about
		player.setShiftKeyDown(true);
		PlayerTick.tick(player, 0);
		helper.assertTrue(PlayerTick.isSquid(player), "squid form on");
		ElementHolder holder = SquidDisplay.holderOf(player);
		helper.assertTrue(holder != null, "a blob rides the squid");
		helper.assertValueEqual(holder.getElements().size(), 1, "one blob element");
		helper.assertTrue(holder.getAttachment() != null, "attached to the player");
		// The squid's own player is never sent it: the holder refuses to start watching them, which is a
		// per-viewer thing a game test has no second connection to see, so the rule is asked directly.
		helper.assertTrue(SquidDisplay.hiddenFrom(player, player.getUUID()), "the squid never sees its own blob");
		helper.assertTrue(!SquidDisplay.hiddenFrom(player, UUID.randomUUID()), "everybody else does");
		// Lying still in its own ink is how a squid hides, so the blob is not drawn at all. The first tick
		// has no measured movement, which is exactly that case.
		helper.assertTrue(!SquidDisplay.isShown(player), "a still squid in its own ink shows nothing");
		// A second tick with the squid moved along keeps the one blob rather than making another, and
		// shows it: anything that leaves a wake is worth seeing.
		Vec3 stepped = player.position().add(0.3, 0, 0);
		player.setPos(stepped.x, stepped.y, stepped.z);
		PlayerTick.tick(player, 1);
		helper.assertTrue(SquidDisplay.holderOf(player) == holder, "the same blob, turned rather than replaced");
		helper.assertTrue(SquidDisplay.isShown(player), "a swimming squid is a blob");
		// And stopping hides it again, without taking the holder down.
		PlayerTick.tick(player, 2);
		helper.assertTrue(!SquidDisplay.isShown(player), "holding still hides it again");
		helper.assertTrue(SquidDisplay.holderOf(player) == holder, "the holder is not rebuilt for it");
		player.setShiftKeyDown(false);
		PlayerTick.tick(player, 3);
		helper.assertTrue(SquidDisplay.holderOf(player) == null, "the blob goes with the form");
		helper.assertTrue(holder.getAttachment() == null || holder.getAttachment().isRemoved(), "and its attachment with it");
		player.discard();
		helper.succeed();
	}

	/**
	 * Squid form holds over the ink, not only in it. A jump or a ledge takes the paint out from under a
	 * squid's feet for a few ticks, and dropping the form (with the invisibility) for that is what the
	 * user saw as "you go out of invisibility because you were away from ink too long". Ink anywhere in
	 * the four cells below the feet holds it, and a ten-tick grace carries the gap after that.
	 */
	@GameTest
	public void squidStaysSquidOverInk(GameTestHelper helper) {
		stoneFloor(helper, 5); // floor at y=1
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 1, 4)), Direction.UP, PaintColor.DATA);
		Vec3 inTheInk = helper.absoluteVec(new Vec3(4.5, 2.0, 4.5)); // the painted cell itself
		player.setPos(inTheInk.x, inTheInk.y, inTheInk.z);
		player.setShiftKeyDown(true);
		PlayerTick.tick(player, 0);
		helper.assertTrue(PlayerTick.isSquid(player), "squid in its own ink");
		// Two blocks up: nothing under the feet, paint two cells below. Still a squid, tick after tick.
		Vec3 jumped = helper.absoluteVec(new Vec3(4.5, 4.0, 4.5));
		player.setPos(jumped.x, jumped.y, jumped.z);
		helper.assertTrue(PlayerTick.paintUnder(player) == null, "no paint in the cell the feet are in");
		helper.assertTrue(PlayerTick.inkBelow(player, PaintColor.DATA), "but ink below");
		for (long now = 1; now <= 5; now++) PlayerTick.tick(player, now);
		helper.assertTrue(PlayerTick.isSquid(player), "squid form holds over its own ink");
		helper.assertTrue(player.hasEffect(MobEffects.INVISIBILITY), "and stays invisible");
		// Five blocks up: out of reach of the ink, so only the grace is holding it now.
		Vec3 high = helper.absoluteVec(new Vec3(4.5, 7.0, 4.5));
		player.setPos(high.x, high.y, high.z);
		helper.assertTrue(!PlayerTick.inkBelow(player, PaintColor.DATA), "too high for the ink below");
		PlayerTick.tick(player, 6);
		helper.assertTrue(PlayerTick.isSquid(player), "the grace carries the gap");
		PlayerTick.tick(player, 16);
		helper.assertTrue(!PlayerTick.isSquid(player), "and runs out: no ink, no squid");
		helper.succeed();
	}

	/**
	 * A bottom slab's paint lands as display quads keyed one cell above the slab (like a stair tread), but
	 * a player standing on the slab has {@code blockPosition()} at the slab's own cell, one below that.
	 * {@code paintUnder} must still find it by falling back to the cell above the feet.
	 */
	/**
	 * A swimming squid leaves a wake and a still one does not. Particles leave nothing behind on the
	 * server to assert on, so {@link PlayerTick#ripples} answers with how many it sent.
	 */
	@GameTest
	public void squidSwimLeavesRipples(GameTestHelper helper) {
		Player player = gunner(helper);
		ServerLevel level = helper.getLevel();
		// Two dust pillars and a crumb: the pillars are the mace-smash particle, which is what makes the
		// wake read as a mass of ink rather than as grit.
		helper.assertValueEqual(PlayerTick.ripples(level, player, PaintColor.DATA, new Vec3(0.2, 0, 0)), 3, "swimming east leaves a wake");
		helper.assertValueEqual(PlayerTick.ripples(level, player, PaintColor.IT, new Vec3(0, 0, -0.2)), 3, "swimming north too");
		helper.assertTrue(Painter.pillar(PaintColor.DATA).getType() == ParticleTypes.DUST_PILLAR, "the wake is dust pillars");
		helper.assertTrue(Painter.pillar(PaintColor.DATA).getState().getBlock() == Painter.crumbs(PaintColor.DATA).getState().getBlock(),
				"carrying the same paint state the crumbs do, so it comes out in the team colour");
		helper.assertValueEqual(PlayerTick.ripples(level, player, PaintColor.DATA, Vec3.ZERO), 0, "a still squid leaves nothing");
		// Only horizontal movement counts: falling is not swimming, and a crawl under the threshold is
		// the squid holding position rather than moving.
		helper.assertValueEqual(PlayerTick.ripples(level, player, PaintColor.DATA, new Vec3(0, -0.8, 0)), 0, "falling is not swimming");
		helper.assertValueEqual(PlayerTick.ripples(level, player, PaintColor.DATA, new Vec3(0.01, 0, 0.01)), 0, "a crawl is not swimming");
		helper.succeed();
	}

	/**
	 * Invisibility hides the body but not the gun, so squid form lies to everyone else's client about
	 * what is in the hands. The lie and the truth are built here; the broadcast itself needs a second
	 * tracking player, which a game test has no way to make.
	 */
	@GameTest
	public void squidHidesHeldItemsFromOthers(GameTestHelper helper) {
		Player player = gunner(helper);
		player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.STICK));
		List<Pair<EquipmentSlot, ItemStack>> hidden = SquidState.hiddenEquipment(player);
		helper.assertTrue(!hidden.isEmpty(), "the lie covers some slots");
		Set<EquipmentSlot> lied = new HashSet<>();
		for (Pair<EquipmentSlot, ItemStack> slot : hidden) {
			helper.assertTrue(slot.getSecond().isEmpty(), "hidden " + slot.getFirst() + " is empty");
			lied.add(slot.getFirst());
		}
		helper.assertTrue(lied.contains(EquipmentSlot.MAINHAND) && lied.contains(EquipmentSlot.OFFHAND), "both hands are hidden");
		List<Pair<EquipmentSlot, ItemStack>> real = SquidState.realEquipment(player);
		helper.assertValueEqual(real.size(), hidden.size(), "the truth covers the same slots as the lie");
		ItemStack mainhand = ItemStack.EMPTY;
		ItemStack offhand = ItemStack.EMPTY;
		for (Pair<EquipmentSlot, ItemStack> slot : real) {
			if (slot.getFirst() == EquipmentSlot.MAINHAND) mainhand = slot.getSecond();
			if (slot.getFirst() == EquipmentSlot.OFFHAND) offhand = slot.getSecond();
		}
		helper.assertTrue(mainhand.getItem() instanceof PaintWeapon, "the truth still carries the paint gun, not " + mainhand);
		helper.assertValueEqual(offhand.getItem(), Items.STICK, "and whatever is in the off hand");
		helper.succeed();
	}

	@GameTest
	public void squidDetectsPaintOnSlabTread(GameTestHelper helper) {
		BlockPos slab = new BlockPos(4, 2, 4);
		helper.setBlock(slab, Blocks.STONE_SLAB.defaultBlockState());
		Player player = mockPlayer(helper, GameType.SURVIVAL);
		Vec3 at = helper.absoluteVec(new Vec3(4.5, 2.5, 4.5)); // standing on top of the bottom slab
		player.setPos(at.x, at.y, at.z);
		boolean painted = Painter.paintFace(helper.getLevel(), helper.absolutePos(slab), Direction.UP, PaintColor.DATA);
		helper.assertTrue(painted, "slab top accepted paint");
		helper.assertTrue(PaintDisplays.of(helper.getLevel()).colorAt(helper.absolutePos(slab)) == null,
				"quads are keyed one cell above the slab, not at the slab's own cell");
		helper.assertTrue(PlayerTick.paintUnder(player) == PaintColor.DATA,
				"paint on the slab tread is found from the player's feet cell below it");
		helper.succeed();
	}

	/** Pushing against an own-colour painted wall while a squid lifts the player. */
	@GameTest
	public void squidWallSwim(GameTestHelper helper) {
		stoneFloor(helper, 5);
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(4, y, 2), Blocks.STONE);
		ServerPlayer player = wallSquid(helper, PaintColor.DATA);
		// Hugging the wall: its west plane is the x of relative cell 4, and a full-size player's box
		// reaches 0.3 either side of its centre.
		Vec3 at = helper.absoluteVec(new Vec3(3.7, 2.0, 2.5));
		player.setPos(at.x, at.y, at.z);
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 2)), Direction.UP, PaintColor.DATA); // floor under
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 2)), Direction.WEST, PaintColor.DATA); // wall beside
		player.setShiftKeyDown(true);
		player.setYRot(-90f); // forward is +X, into the wall
		player.setLastClientInput(PUSHING);
		player.setDeltaMovement(0.1, 0, 0);
		// The tick squid form is entered is the dive: the surge is that tick's one velocity packet, and a
		// climb packet on top of it would overwrite the surge. The climb is the tick after.
		PlayerTick.tick(player, 0);
		helper.assertTrue(SquidState.isSquid(player), "squid");
		player.setDeltaMovement(0.1, 0, 0);
		PlayerTick.tick(player, 1);
		helper.assertTrue(player.getDeltaMovement().y > 0.2, "lifted up the inked wall, dy=" + player.getDeltaMovement().y);
		// Only floor paint left: the wall itself carries no paint, so the squid must not climb it.
		helper.setBlock(new BlockPos(4, 2, 2), Blocks.AIR);
		player.setDeltaMovement(0.1, 0, 0);
		PlayerTick.tick(player, 2);
		helper.assertTrue(player.getDeltaMovement().y < 0.2, "not lifted without a painted wall, dy=" + player.getDeltaMovement().y);
		helper.succeed();
	}

	/**
	 * The same climb up a wall that is not a full cube. A pane's paint is display quads in the player's
	 * own cell rather than a paint block face, so this is the other half of {@code paintedWallBeside}:
	 * the quads must carry the face pointing back from the pane at the player.
	 */
	@GameTest
	public void squidWallSwimUpAPane(GameTestHelper helper) {
		stoneFloor(helper, 5);
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(4, y, 2), Blocks.GLASS_PANE);
		ServerPlayer player = wallSquid(helper, PaintColor.DATA);
		Vec3 at = helper.absoluteVec(new Vec3(3.7, 2.0, 2.5)); // hugging the pane's cell
		player.setPos(at.x, at.y, at.z);
		BlockPos feet = helper.absolutePos(new BlockPos(3, 2, 2));
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 2)), Direction.UP, PaintColor.DATA); // floor under
		helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 2)), Direction.WEST, PaintColor.DATA),
				"the pane took paint");
		PaintDisplays displays = PaintDisplays.of(helper.getLevel());
		helper.assertTrue(displays.colorAt(feet) == PaintColor.DATA && displays.faceAt(feet) == Direction.WEST,
				"a pane's paint is quads in the player's own cell, facing back at the pane");
		// A wall cell's paint attaches the other way: the quads carry the EAST state, pointing at the pane.
		for (BlockState quad : displays.statesAt(feet)) {
			helper.assertValueEqual(quad, PaintStates.connected(PaintColor.DATA, Direction.EAST, 0),
					"the DATA wall state, with nothing painted in the plane beside it to border against");
		}
		player.setShiftKeyDown(true);
		player.setYRot(-90f); // forward is +X, into the pane
		player.setLastClientInput(PUSHING);
		player.setDeltaMovement(0.1, 0, 0);
		// The tick squid form is entered is the dive: the surge is that tick's one velocity packet, and a
		// climb packet on top of it would overwrite the surge. The climb is the tick after.
		PlayerTick.tick(player, 0);
		helper.assertTrue(SquidState.isSquid(player), "squid");
		player.setDeltaMovement(0.1, 0, 0);
		PlayerTick.tick(player, 1);
		helper.assertTrue(player.getDeltaMovement().y > 0.2, "lifted up the inked pane, dy=" + player.getDeltaMovement().y);
		// The quads are the only thing holding the climb up: take the pane away and the cell's quads die
		// with it, so the same push must go nowhere.
		helper.setBlock(new BlockPos(4, 2, 2), Blocks.AIR);
		displays.count(helper.getLevel()); // the sweep that drops cells whose surface is gone
		player.setDeltaMovement(0.1, 0, 0);
		PlayerTick.tick(player, 2);
		helper.assertTrue(player.getDeltaMovement().y < 0.2, "not lifted once the pane is gone, dy=" + player.getDeltaMovement().y);
		helper.succeed();
	}

	/**
	 * No paint under the feet at all — only a painted wall beside the player. Squid form must still
	 * hold (a climb off the floor paint would otherwise end squid form and drop the player mid-wall),
	 * and pressing into the wall climbs it while easing off holds the squid in place instead of
	 * sliding back down.
	 */
	@GameTest
	public void squidClingsToAnInkedWall(GameTestHelper helper) {
		stoneFloor(helper, 5);
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(4, y, 2), Blocks.STONE);
		ServerPlayer player = wallSquid(helper, PaintColor.DATA);
		Vec3 at = helper.absoluteVec(new Vec3(3.7, 2.0, 2.5));
		player.setPos(at.x, at.y, at.z);
		// Painted one cell up from the feet (head height): a full-cube wall face lands as a real
		// paint block in the cell in front of it — the feet cell itself if painted at feet height, which
		// paintUnder would find directly and defeat the point of this test. Painting at head height
		// instead keeps the feet cell (and its own paintUnder check) genuinely clean.
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 3, 2)), Direction.WEST, PaintColor.DATA);
		helper.assertTrue(PlayerTick.paintUnder(player) == null, "no paint under the feet");
		player.setShiftKeyDown(true);
		player.setYRot(-90f); // facing the wall, but not asking to move
		player.setLastClientInput(Input.EMPTY);
		player.setDeltaMovement(0.0, -0.05, 0.0);
		PlayerTick.tick(player, 0);
		helper.assertTrue(PlayerTick.isSquid(player), "squid form holds beside a wall with no floor paint");
		// The cling is gravity switched off, not a velocity packet: nothing touches the player's motion.
		assertClinging(helper, player, "beside the wall");
		helper.assertValueEqual(player.getDeltaMovement().y, -0.05, "the cling leaves the player's own motion alone");
		// A squid's box is half as wide, so hugging the same wall puts its centre closer to it.
		Vec3 hug = helper.absoluteVec(new Vec3(3.85, 2.0, 2.5));
		player.setPos(hug.x, hug.y, hug.z);
		player.setLastClientInput(PUSHING);
		player.setDeltaMovement(0.1, 0, 0);
		PlayerTick.tick(player, 1);
		helper.assertTrue(PlayerTick.isSquid(player), "still squid while pushing into the wall");
		double dy = player.getDeltaMovement().y;
		helper.assertTrue(dy > 0.35 && dy < 0.5, "climbs the wall at the wall-swim speed, dy=" + dy);
		helper.assertTrue(!player.getAttribute(Attributes.GRAVITY).hasModifier(SquidState.CLING_ID),
				"a climbing squid is not clinging");
		helper.succeed();
	}

	/** A squid held on a wall by gravity alone: the modifier is on and the attribute is exactly zero. */
	private static void assertClinging(GameTestHelper helper, Player player, String what) {
		AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
		helper.assertTrue(gravity != null && gravity.hasModifier(SquidState.CLING_ID), what + ": clinging by gravity");
		helper.assertValueEqual(gravity.getValue(), 0.0, what + ": gravity is off");
	}

	/**
	 * A squid that jumps off an inked wall keeps its momentum. The cling used to be a velocity packet
	 * built from the server's own delta, sent every tick a wall was beside the squid, which overwrote
	 * the client's real motion — the jump impulse included. Now: no packet at all while the measured
	 * movement is already a jump, and no cling modifier to hold the arc down either.
	 */
	@GameTest
	public void squidJumpKeepsMomentum(GameTestHelper helper) {
		stoneFloor(helper, 5);
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(4, y, 2), Blocks.STONE);
		ServerPlayer player = wallSquid(helper, PaintColor.DATA);
		Vec3 at = helper.absoluteVec(new Vec3(3.7, 2.0, 2.5));
		player.setPos(at.x, at.y, at.z);
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 2)), Direction.WEST, PaintColor.DATA);
		player.setShiftKeyDown(true);
		player.setYRot(-90f); // forward is +X, into the wall
		player.setLastClientInput(PUSHING);
		PlayerTick.tick(player, 0); // squid, and the tick that records where the player is
		helper.assertTrue(PlayerTick.isSquid(player), "squid beside the inked wall");
		// A jump: 0.3 blocks along the wall and 0.75 up since the last tick — a squid's own hop, which is
		// what the client actually did and what the server can only see by measuring.
		Vec3 jumped = at.add(0.0, 0.75, 0.3);
		player.setPos(jumped.x, jumped.y, jumped.z);
		player.setDeltaMovement(Vec3.ZERO);
		player.syncVelocity = false;
		int syncs = PlayerTick.velocitySyncs();
		PlayerTick.tick(player, 1);
		helper.assertValueEqual(PlayerTick.velocitySyncs(), syncs, "a jumping squid gets no velocity packet");
		helper.assertTrue(!player.syncVelocity, "nothing queued a velocity sync this tick");
		helper.assertValueEqual(player.getDeltaMovement(), Vec3.ZERO, "and the player's own motion is untouched");
		helper.assertTrue(!player.getAttribute(Attributes.GRAVITY).hasModifier(SquidState.CLING_ID),
				"no cling while the squid is on its way up");
		helper.succeed();
	}

	/**
	 * The climb keeps going past the first block. Pushing into the wall is read from the client's own
	 * input, not from {@code horizontalCollision}: a real player walking into a wall has their movement
	 * clipped client-side and sends a delta of about zero, so the server copy never collides and the
	 * old check only ever lifted the one block squid form's taller step height carried them over.
	 */
	@GameTest
	public void squidClimbsAnInkedWall(GameTestHelper helper) {
		stoneFloor(helper, 5);
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(2, y, 1), Blocks.STONE);
		ServerPlayer player = wallSquid(helper, PaintColor.DATA);
		for (int y = 2; y <= 4; y++) {
			helper.assertTrue(Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(2, y, 1)), Direction.SOUTH, PaintColor.DATA),
					"the wall took paint at y=" + y);
		}
		// Hugging the wall: its south plane is the z of relative cell 2, and a full-size player's box
		// reaches 0.3 either side of its centre.
		Vec3 at = helper.absoluteVec(new Vec3(2.5, 2.0, 2.3));
		player.setPos(at.x, at.y, at.z);
		player.setYRot(180f); // forward is -Z, into the wall
		player.setShiftKeyDown(true);
		player.setLastClientInput(PUSHING);
		player.setDeltaMovement(0, -0.08, 0);
		// The dive tick first: its surge is that tick's velocity packet, so the climb is the tick after.
		PlayerTick.tick(player, 0);
		helper.assertTrue(SquidState.isSquid(player), "squid");
		player.setDeltaMovement(0, -0.08, 0);
		PlayerTick.tick(player, 1);
		double first = player.getDeltaMovement().y;
		helper.assertTrue(first > 0.35 && first < 0.5, "lifted off the floor at the wall-swim speed, dy=" + first);
		// A block higher up the same wall — the case the user reported as "not working past one block".
		// A squid's box is half as wide, so hugging the wall puts its centre closer to it.
		Vec3 higher = helper.absoluteVec(new Vec3(2.5, 3.0, 2.15));
		player.setPos(higher.x, higher.y, higher.z);
		// Teleporting the player a whole block up reads, quite correctly, as a jump: the climb never
		// sends a packet over someone already rising faster than it would push them. One tick lets the
		// measurement settle at the new spot, and the tick after that is the climb this is about.
		PlayerTick.tick(player, 2);
		player.setDeltaMovement(0, -0.08, 0);
		PlayerTick.tick(player, 3);
		double second = player.getDeltaMovement().y;
		helper.assertTrue(second > 0.35 && second < 0.5, "still lifted a block higher up the wall, dy=" + second);
		// Off the keys: the squid clings where it is rather than climbing on by itself.
		player.setLastClientInput(Input.EMPTY);
		player.setDeltaMovement(0, -0.08, 0);
		int syncs = PlayerTick.velocitySyncs();
		PlayerTick.tick(player, 4);
		helper.assertTrue(PlayerTick.isSquid(player), "squid form holds while clinging");
		assertClinging(helper, player, "off the keys");
		// Zero gravity stops a squid falling further but does not take away the speed it already had, so
		// the tick the cling begins sends one flattening packet.
		helper.assertValueEqual(PlayerTick.velocitySyncs(), syncs + 1, "the cling arrests the fall, once");
		helper.assertValueEqual(player.getDeltaMovement().y, 0.0, "and leaves the squid hanging");
		// Every tick after that is gravity alone: no packets, and the player's motion is the player's.
		player.setDeltaMovement(0, -0.08, 0);
		PlayerTick.tick(player, 5);
		assertClinging(helper, player, "still clinging");
		helper.assertValueEqual(PlayerTick.velocitySyncs(), syncs + 1, "no packet on the ticks after it");
		helper.assertValueEqual(player.getDeltaMovement().y, -0.08, "and nothing touches the motion");
		// Facing away from the wall is not a climb either, however hard the player pushes.
		player.setYRot(0f); // forward is +Z, away from the wall
		player.setLastClientInput(PUSHING);
		player.setDeltaMovement(0, -0.08, 0);
		PlayerTick.tick(player, 6);
		assertClinging(helper, player, "pushing away from the wall");
		helper.assertValueEqual(PlayerTick.velocitySyncs(), syncs + 1, "still no velocity packet");
		helper.succeed();
	}

	/** Enemy ink drips: 1 damage every 20 ticks in survival, never below 1 health. */
	@GameTest
	public void enemyInkDripDamage(GameTestHelper helper) {
		helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE);
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Painter.paintFace(helper.getLevel(), helper.absolutePos(new BlockPos(4, 2, 4)), Direction.UP, PaintColor.IT);
		float before = player.getHealth();
		PlayerTick.tick(player, 20);
		helper.assertTrue(player.getHealth() <= before - 1.0f, "hurt on a damage tick, health " + player.getHealth());
		// The guard itself, isolated from vanilla's post-hit invulnerability: at 1.5 health a drip would
		// take the player below one, so it must not land at all.
		player.setHealth(1.5f);
		player.damageCooldownTime = 0;
		PlayerTick.tick(player, 40);
		helper.assertTrue(player.getHealth() == 1.5f, "guard skips the drip below one health, health " + player.getHealth());
		// At 3.0 a drip lands as normal.
		player.setHealth(3.0f);
		player.damageCooldownTime = 0;
		PlayerTick.tick(player, 60);
		helper.assertTrue(player.getHealth() == 2.0f, "drip lands with health to spare, health " + player.getHealth());
		// Never below one health: without resetting damageCooldownTime this would be vacuous, since vanilla
		// itself rejects a second hit within the previous drip's invulnerability window.
		player.setHealth(1.5f);
		player.damageCooldownTime = 0;
		PlayerTick.tick(player, 80);
		helper.assertTrue(player.getHealth() == 1.5f, "never below one health, health " + player.getHealth());
		player.setHealth(before);
		helper.succeed();
	}

	/**
	 * The shooter's ball takes two bounces before an impact spends it: two reflections off the floor,
	 * then the third hit ends it. Dropped straight down, so every reflection is a clean upward one.
	 */
	@GameTest(maxTicks = 100)
	public void paintBallBouncesTwice(GameTestHelper helper) {
		stoneFloor(helper, 5);
		PaintBall ball = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.DATA, Weapon.SHOOTER_BOUNCES, 0);
		Vec3 at = helper.absoluteVec(new Vec3(2.5, 4, 2.5));
		ball.setPos(at.x, at.y, at.z);
		ball.setDeltaMovement(0, -0.8, 0);
		helper.getLevel().addFreshEntity(ball);
		// The impact ticks are not known in advance, so sample every tick: a drop in bouncesLeft is a
		// reflection, the last downward reading before the first is the incoming speed, and the first
		// upward one after it is what that bounce gave back.
		double[] falling = {0};
		double[] reflected = {0};
		int[] left = {Weapon.SHOOTER_BOUNCES};
		int[] reflections = {0};
		for (int tick = 1; tick <= 70; tick++) {
			helper.runAfterDelay(tick, () -> {
				double dy = ball.getDeltaMovement().y;
				if (ball.bouncesLeft() < left[0]) {
					left[0] = ball.bouncesLeft();
					reflections[0]++;
				}
				if (reflections[0] == 0 && dy < 0) falling[0] = dy;
				if (reflections[0] == 1 && reflected[0] == 0 && dy > 0) reflected[0] = dy;
			});
		}
		helper.runAfterDelay(6, () -> {
			helper.assertTrue(!ball.isRemoved(), "still flying after the first impact");
			helper.assertValueEqual(ball.bouncesLeft(), Weapon.SHOOTER_BOUNCES - 1, "one of the two bounces used");
			helper.assertTrue(isPaint(helper.getBlockState(new BlockPos(2, 2, 2)), PaintColor.DATA), "first impact painted");
			// Neither reading is the instant of the bounce — the ball was still accelerating when the last
			// downward one was taken, and drag and gravity had already run when the upward one was — so
			// the ratio lands near BOUNCE_RESTITUTION rather than on it.
			helper.assertTrue(reflected[0] > 0, "the bounce sends the ball back up, dy=" + reflected[0]);
			double kept = reflected[0] / -falling[0];
			helper.assertTrue(Math.abs(kept - PaintBall.BOUNCE_RESTITUTION) < 0.10,
					"the bounce keeps about " + PaintBall.BOUNCE_RESTITUTION + " of the incoming speed, kept " + kept);
		});
		helper.runAfterDelay(75, () -> {
			helper.assertValueEqual(reflections[0], Weapon.SHOOTER_BOUNCES, "both bounces reflected the ball");
			helper.assertTrue(ball.isRemoved(), "gone after the impact that follows the last bounce");
			// The droplets each bounce threw off outlive nothing, but a stray one mid-flight would sail
			// into the next test structure.
			helper.getEntities(PaintBall.TYPE, new BlockPos(2, 2, 2), 8.0).forEach(Entity::discard);
			helper.succeed();
		});
	}

	/** Every bounce throws off a couple of droplets, and those droplets throw off none of their own. */
	@GameTest(maxTicks = 40)
	public void bounceSpawnsDroplets(GameTestHelper helper) {
		stoneFloor(helper, 5);
		PaintBall ball = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.DATA, 1, 0);
		Vec3 at = helper.absoluteVec(new Vec3(2.5, 4, 2.5));
		ball.setPos(at.x, at.y, at.z);
		ball.setDeltaMovement(0, -0.8, 0);
		helper.getLevel().addFreshEntity(ball);
		// Droplets live eight ticks and are thrown at an impact tick that is not known in advance, so
		// look every tick and judge them on the first one they exist, while they still carry what the
		// bounce gave them.
		List<PaintBall> seen = new ArrayList<>();
		List<Vec3> thrownAt = new ArrayList<>();
		List<Vec3> thrownWith = new ArrayList<>();
		for (int tick = 1; tick <= 10; tick++) {
			helper.runAfterDelay(tick, () -> {
				if (!seen.isEmpty()) return;
				for (PaintBall drop : helper.getEntities(PaintBall.TYPE, new BlockPos(2, 2, 2), 8.0)) {
					if (!drop.isDroplet()) continue;
					// Read here, not at the end: a droplet is only briefly carrying what the bounce gave it.
					seen.add(drop);
					thrownAt.add(drop.position());
					thrownWith.add(drop.getDeltaMovement());
				}
			});
		}
		helper.runAfterDelay(11, () -> {
			helper.assertValueEqual(ball.bouncesLeft(), 0, "the ball has bounced");
			helper.assertTrue(!ball.isDroplet(), "the ball itself is not one of its own droplets");
			helper.assertValueEqual(seen.size(), 2, "the bounce threw off two droplets");
			Vec3 impact = helper.absoluteVec(new Vec3(2.5, 2.125, 2.5));
			for (int i = 0; i < seen.size(); i++) {
				PaintBall drop = seen.get(i);
				helper.assertValueEqual(drop.bouncesLeft(), 0, "a droplet does not bounce");
				helper.assertValueEqual(drop.splatRadius(), 0, "a droplet paints a single face");
				helper.assertTrue(thrownWith.get(i).y > 0,
						"a droplet leaves along the reflection, upward off a floor, dy=" + thrownWith.get(i).y);
				// Thrown from the bounce, not from the gun: the shooter stands at (4, 3, 4).
				double away = thrownAt.get(i).distanceTo(impact);
				helper.assertTrue(away < 1.5, "a droplet starts at the impact, " + away + " from it");
			}
			helper.getEntities(PaintBall.TYPE, new BlockPos(2, 2, 2), 8.0).forEach(Entity::discard);
			helper.succeed();
		});
	}

	/** A sprayer droplet with a 12-tick lifetime splashes the floor beneath it when time runs out. */
	@GameTest
	public void dropletSplashesAfterLifetime(GameTestHelper helper) {
		stoneFloor(helper, 5);
		PaintBall drop = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.DATA, 0, 12);
		Vec3 at = helper.absoluteVec(new Vec3(2.5, 3.2, 2.5));
		drop.setPos(at.x, at.y, at.z);
		drop.setDeltaMovement(0, 0.02, 0); // hovering: only the lifetime can end it
		drop.setNoGravity(true);
		helper.getLevel().addFreshEntity(drop);
		helper.runAfterDelay(16, () -> {
			helper.assertTrue(drop.isRemoved(), "droplet expired");
			helper.assertTrue(isPaint(helper.getBlockState(new BlockPos(2, 2, 2)), PaintColor.DATA), "floor under the droplet painted");
			helper.succeed();
		});
	}

	/** The blob display follows the ball and is torn down with it. */
	@GameTest
	public void blobFollowsTheBall(GameTestHelper helper) {
		PaintBall ball = new PaintBall(helper.getLevel(), gunner(helper), PaintColor.DATA, 0, 0);
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
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		InteractionResult result = PaintWeapon.of(Weapon.SPRAYER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result.consumesAction(), "sprays");
		List<PaintBall> drops = helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0);
		helper.assertValueEqual(drops.size(), 3, "three droplets");
		for (PaintBall drop : drops) {
			helper.assertValueEqual(drop.bouncesLeft(), 0, "droplets do not bounce");
			helper.assertValueEqual(drop.splatRadius(), 0, "droplets paint a single face");
		}
		helper.assertValueEqual(Ink.get(player.getItemInHand(InteractionHand.MAIN_HAND)), Ink.MAX - Weapon.SPRAYER.inkPerShot, "ink cost");
		helper.assertTrue(player.getCooldowns().isOnCooldown(player.getItemInHand(InteractionHand.MAIN_HAND)),
				"the sprayer goes on cooldown: the fire rate is the cooldown");
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
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
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
		helper.assertTrue(player.getCooldowns().isOnCooldown(player.getItemInHand(InteractionHand.MAIN_HAND)),
				"the slosher goes on cooldown: the fire rate is the cooldown");
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
			helper.assertTrue(Weapon.byId(weapon.commandId()).orElse(null) == weapon,
					"byId also takes the lowercase name " + weapon.commandId());
		}
		helper.assertTrue(Weapon.byId("shooter").orElse(null) == Weapon.SHOOTER, "the shooter answers to \"shooter\"");
		helper.assertTrue(Weapon.byId("paint_gun").orElse(null) == Weapon.SHOOTER, "and still to its registry id");
		helper.assertTrue(Weapon.idList().contains("shooter") && !Weapon.idList().contains("paint_gun"),
				"the help offers \"shooter\", not the registry id: " + Weapon.idList());
		helper.assertTrue(Weapon.byId("nonesuch").isEmpty(), "an unknown id resolves to nothing");
		player.getInventory().clearContent();
		helper.succeed();
	}

	/** The charger's shot paints the floor under the scanned line and splats where it ends. */
	@GameTest
	public void chargerPaintsALineUnderTheScan(GameTestHelper helper) {
		stoneFloor(helper, 7); // floor at y=1, x/z 0..6
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(6, y, 3), Blocks.STONE); // end wall
		Player player = gunner(helper);
		ItemStack charger = new ItemStack(PaintWeapon.of(Weapon.CHARGER));
		player.setItemInHand(InteractionHand.MAIN_HAND, charger);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Vec3 at = helper.absoluteVec(new Vec3(0.5, 2.0, 3.5));
		player.setPos(at.x, at.y, at.z);
		player.setYRot(-90f); // look +X
		player.setXRot(0f);
		boolean fired = PaintWeapon.of(Weapon.CHARGER).chargerShot(helper.getLevel(), player, charger, 1.0f);
		helper.assertTrue(fired, "full charge fires");
		int painted = 0;
		for (int x = 1; x <= 5; x++) {
			if (isPaint(helper.getBlockState(new BlockPos(x, 2, 3)), PaintColor.DATA)) painted++;
		}
		helper.assertTrue(painted >= 3, "floor painted along the line, got " + painted);
		helper.assertTrue(hasFace(helper.getBlockState(new BlockPos(5, 2, 3)), PaintColor.DATA, Direction.EAST), "end wall splatted");
		helper.assertValueEqual(Ink.get(charger), Ink.MAX - 12, "full charge costs 12");
		helper.succeed();
	}

	/**
	 * Someone standing in the line stops it: the paint lands under their feet, and the wall they were
	 * standing in front of is left clean.
	 */
	@GameTest
	public void chargerSplashesUnderAHitPlayer(GameTestHelper helper) {
		stoneFloor(helper, 7); // floor at y=1, x/z 0..6
		for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(6, y, 3), Blocks.STONE); // the wall behind them
		Player player = gunner(helper);
		ItemStack charger = new ItemStack(PaintWeapon.of(Weapon.CHARGER));
		player.setItemInHand(InteractionHand.MAIN_HAND, charger);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Vec3 at = helper.absoluteVec(new Vec3(0.5, 2.0, 3.5));
		player.setPos(at.x, at.y, at.z);
		player.setYRot(-90f); // look +X
		player.setXRot(0f);
		// The mock-player helper builds a player but never adds it to the level, and the scan only sees
		// entities the level knows about, so this one has to be put there by hand. On DATA with the
		// shooter, so the line is stopped by a body rather than by a kill: what is asserted below is
		// where the paint went, and a target that took ten hearts and died would take its hitbox with it.
		Player target = mockPlayer(helper, GameType.SURVIVAL);
		helper.getLevel().getScoreboard().addPlayerToTeam(target.getScoreboardName(), team(helper, PaintColor.DATA));
		Vec3 stand = helper.absoluteVec(new Vec3(3.5, 2.0, 3.5));
		target.setPos(stand.x, stand.y, stand.z);
		helper.getLevel().addFreshEntity(target);
		boolean fired = PaintWeapon.of(Weapon.CHARGER).chargerShot(helper.getLevel(), player, charger, 1.0f);
		helper.assertTrue(fired, "full charge fires");
		helper.assertTrue(isPaint(helper.getBlockState(new BlockPos(3, 2, 3)), PaintColor.DATA),
				"the floor under the player in the way is painted");
		helper.assertTrue(!isPaint(helper.getBlockState(new BlockPos(5, 2, 3)), PaintColor.DATA),
				"the line stopped at the player: the wall behind them is clean");
		target.discard();
		helper.succeed();
	}

	/**
	 * Letting go of the scope is not a shot. The charger's two buttons are the scope (right, held) and
	 * the trigger (left), so a release fires nothing, costs nothing and paints nothing — however long
	 * the charge was held for.
	 */
	@GameTest
	public void chargerReleaseDoesNotFire(GameTestHelper helper) {
		stoneFloor(helper, 5);
		Player player = gunner(helper);
		ItemStack charger = new ItemStack(PaintWeapon.of(Weapon.CHARGER));
		player.setItemInHand(InteractionHand.MAIN_HAND, charger);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Vec3 at = helper.absoluteVec(new Vec3(0.5, 2.0, 2.5));
		player.setPos(at.x, at.y, at.z);
		player.setYRot(-90f); // look +X, down the floor
		player.setXRot(0f);
		player.startUsingItem(InteractionHand.MAIN_HAND);
		boolean fired = PaintWeapon.of(Weapon.CHARGER).releaseUsing(charger, helper.getLevel(), player,
				Weapon.CHARGE_MAX_TICKS - Weapon.CHARGE_FULL_TICKS);
		helper.assertTrue(!fired, "a release is not a shot");
		helper.assertValueEqual(Ink.get(charger), Ink.MAX, "and costs nothing");
		for (int x = 1; x <= 4; x++) {
			helper.assertTrue(!isPaint(helper.getBlockState(new BlockPos(x, 2, 2)), PaintColor.DATA),
					"nothing painted at x=" + x);
		}
		helper.succeed();
	}

	/**
	 * Left click fires the charger at whatever charge the scope has built. Right click scopes and the
	 * charge runs up while it is held; the swing packet arrives while the item is in use, which is what
	 * lets one weapon aim with one button and fire with the other.
	 */
	@GameTest(maxTicks = 120)
	public void chargerFiresOnLeftClickWhileScoped(GameTestHelper helper) {
		stoneFloor(helper, 7); // floor at y=1, x/z 0..6
		Player player = gunner(helper);
		ItemStack charger = new ItemStack(PaintWeapon.of(Weapon.CHARGER));
		player.setItemInHand(InteractionHand.MAIN_HAND, charger);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Vec3 at = helper.absoluteVec(new Vec3(0.5, 2.0, 3.5));
		player.setPos(at.x, at.y, at.z);
		player.setYRot(-90f); // look +X
		player.setXRot(0f);
		// The charge only runs down while the player is being ticked, so this one has to be in the level.
		helper.getLevel().addFreshEntity(player);
		InteractionResult scoped = PaintWeapon.of(Weapon.CHARGER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(scoped == InteractionResult.CONSUME, "right click scopes without swinging the arm, got " + scoped);
		helper.assertTrue(player.isUsingItem(), "and the hold has started");
		helper.runAfterDelay(Weapon.CHARGE_FULL_TICKS, () -> {
			helper.assertTrue(player.getTicksUsingItem() >= Weapon.CHARGE_FULL_TICKS,
					"a full charge is held, got " + player.getTicksUsingItem());
			helper.assertTrue(PaintWeapon.leftClick(player), "left click fires");
			helper.assertTrue(!player.isUsingItem(), "and lets go of the scope");
			helper.assertValueEqual(Ink.get(player.getItemInHand(InteractionHand.MAIN_HAND)), Ink.MAX - 12,
					"a full charge costs 12");
			int painted = 0;
			for (int x = 1; x <= 5; x++) {
				if (isPaint(helper.getBlockState(new BlockPos(x, 2, 3)), PaintColor.DATA)) painted++;
			}
			helper.assertTrue(painted >= 3, "a line of paint down the floor, got " + painted);
			player.discard();
			helper.succeed();
		});
	}

	/**
	 * A charger shot refused for an empty tank leaves the player still aiming. Letting go of the scope
	 * for a shot that never happened would throw away the charge as well as the ink.
	 */
	@GameTest
	public void chargerKeepsTheScopeWhenOutOfInk(GameTestHelper helper) {
		Player player = gunner(helper);
		ItemStack charger = new ItemStack(PaintWeapon.of(Weapon.CHARGER));
		player.setItemInHand(InteractionHand.MAIN_HAND, charger);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Ink.set(charger, 0);
		player.startUsingItem(InteractionHand.MAIN_HAND);
		helper.assertTrue(player.isUsingItem(), "scoped");
		helper.assertTrue(!PaintWeapon.leftClick(player), "no shot on a tank that cannot cover it");
		helper.assertTrue(player.isUsingItem(), "and the scope is still up");
		helper.succeed();
	}

	/** Left click without the scope is a snap shot: the minimum charge, so the minimum ink and range. */
	@GameTest
	public void chargerSnapShotWhenUnscoped(GameTestHelper helper) {
		stoneFloor(helper, 7);
		Player player = gunner(helper);
		ItemStack charger = new ItemStack(PaintWeapon.of(Weapon.CHARGER));
		player.setItemInHand(InteractionHand.MAIN_HAND, charger);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		Vec3 at = helper.absoluteVec(new Vec3(0.5, 2.0, 3.5));
		player.setPos(at.x, at.y, at.z);
		player.setYRot(-90f); // look +X
		player.setXRot(0f);
		helper.assertTrue(!player.isUsingItem(), "not scoped");
		helper.assertTrue(PaintWeapon.leftClick(player), "left click still fires");
		helper.assertValueEqual(Ink.get(charger), Ink.MAX - Weapon.CHARGE_BASE_COST, "a snap shot costs the base ink only");
		int painted = 0;
		for (int x = 1; x <= 5; x++) {
			if (isPaint(helper.getBlockState(new BlockPos(x, 2, 3)), PaintColor.DATA)) painted++;
		}
		helper.assertTrue(painted >= 1, "and still paints, got " + painted);
		helper.succeed();
	}

	/**
	 * Left click on the other three weapons throws a splat bomb: one slow, fat, no-bounce ball carrying
	 * the wide splat radius and the blast, for the special's own ink.
	 */
	@GameTest
	public void leftClickThrowsTheSpecial(GameTestHelper helper) {
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		long now = helper.getLevel().getServer().getTickCount();
		helper.assertTrue(PaintWeapon.leftClick(player), "left click throws the special");
		List<PaintBall> balls = helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0);
		helper.assertValueEqual(balls.size(), 1, "one bomb");
		PaintBall bomb = balls.getFirst();
		helper.assertTrue(bomb.isBomb(), "it is a bomb: it goes off where it lands");
		helper.assertValueEqual(bomb.blast(), Weapon.SPECIAL_BLAST, "the blast radius");
		helper.assertValueEqual(bomb.splatRadius(), Weapon.SPECIAL_RADIUS, "the wide splat radius");
		helper.assertValueEqual(bomb.damage(), Weapon.SPECIAL_DAMAGE, "the special's damage");
		helper.assertValueEqual(bomb.lifetime(), Weapon.SPECIAL_LIFETIME, "the special's lifetime");
		helper.assertValueEqual(bomb.bouncesLeft(), 0, "a bomb does not bounce");
		helper.assertValueEqual(bomb.blobScale(), Weapon.SPECIAL_SCALE, "and it is a big blob");
		helper.assertTrue(bomb.getDeltaMovement().y > 0, "lobbed above the crosshair, got " + bomb.getDeltaMovement());
		ItemStack gun = player.getItemInHand(InteractionHand.MAIN_HAND);
		helper.assertValueEqual(Ink.get(gun), Ink.MAX - Weapon.SPECIAL_INK, "the special's ink");
		helper.assertValueEqual(PaintWeapon.specialWait(player, now), (long) Weapon.SPECIAL_COOLDOWN, "and its own wait");
		helper.assertTrue(!player.getCooldowns().isOnCooldown(gun),
				"the special's wait is not the gun's cooldown: the trigger is still free");
		balls.forEach(Entity::discard);
		helper.succeed();
	}

	/** The special has a wait of its own and a price of its own, and refuses when either is not met. */
	@GameTest
	public void specialRespectsItsOwnCooldownAndInk(GameTestHelper helper) {
		PaintWeapon shooter = PaintWeapon.of(Weapon.SHOOTER);
		ServerLevel level = helper.getLevel();
		long now = level.getServer().getTickCount();
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		ItemStack gun = player.getItemInHand(InteractionHand.MAIN_HAND);
		helper.assertTrue(shooter.special(level, player, gun), "the first bomb goes");
		helper.assertValueEqual(PaintWeapon.specialWait(player, now), (long) Weapon.SPECIAL_COOLDOWN, "the wait starts");
		helper.assertTrue(!shooter.special(level, player, gun), "a second bomb inside the wait is refused");
		helper.assertValueEqual(Ink.get(gun), Ink.MAX - Weapon.SPECIAL_INK, "and costs nothing extra");
		helper.assertValueEqual(helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0).size(), 1, "still one bomb");
		helper.assertValueEqual(PaintWeapon.specialWait(player, now + Weapon.SPECIAL_COOLDOWN), 0L, "ready again after the wait");
		// A tank that cannot cover the bomb is refused too, and starts the refill the way a shot does.
		Player poor = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(poor.getScoreboardName(), team(helper, PaintColor.DATA));
		ItemStack low = poor.getItemInHand(InteractionHand.MAIN_HAND);
		Ink.set(low, Weapon.SPECIAL_INK - 1);
		helper.assertTrue(!shooter.special(level, poor, low), "no bomb on a tank that cannot cover it");
		helper.assertTrue(poor.getCooldowns().isOnCooldown(low), "the refill holds the gun instead");
		helper.assertValueEqual(PaintWeapon.specialWait(poor, now), 0L, "and the special is still ready");
		helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0).forEach(Entity::discard);
		helper.succeed();
	}

	/**
	 * A left click with a paint weapon in hand never breaks a block or hits an entity: both Fabric
	 * attack callbacks refuse the vanilla action, so the arena survives the special being thrown at it.
	 * A player holding something else is left alone.
	 */
	@GameTest
	public void attackDoesNotBreakBlocks(GameTestHelper helper) {
		helper.setBlock(new BlockPos(2, 2, 2), Blocks.STONE);
		Player armed = gunner(helper); // on no team, so the click itself throws nothing
		BlockPos stone = helper.absolutePos(new BlockPos(2, 2, 2));
		InteractionResult onBlock = AttackBlockCallback.EVENT.invoker()
				.interact(armed, helper.getLevel(), InteractionHand.MAIN_HAND, stone, Direction.UP);
		helper.assertTrue(onBlock == InteractionResult.FAIL, "a paint weapon does not break blocks, got " + onBlock);
		Player target = mockPlayer(helper, GameType.SURVIVAL);
		InteractionResult onEntity = AttackEntityCallback.EVENT.invoker()
				.interact(armed, helper.getLevel(), InteractionHand.MAIN_HAND, target, null);
		helper.assertTrue(onEntity == InteractionResult.FAIL, "and does not melee, got " + onEntity);
		Player bare = mockPlayer(helper, GameType.SURVIVAL);
		InteractionResult barehanded = AttackBlockCallback.EVENT.invoker()
				.interact(bare, helper.getLevel(), InteractionHand.MAIN_HAND, stone, Direction.UP);
		helper.assertTrue(barehanded == InteractionResult.PASS, "an empty hand is vanilla's business, got " + barehanded);
		Player carrier = mockPlayer(helper, GameType.SURVIVAL);
		carrier.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
		InteractionResult withStone = AttackBlockCallback.EVENT.invoker()
				.interact(carrier, helper.getLevel(), InteractionHand.MAIN_HAND, stone, Direction.UP);
		helper.assertTrue(withStone == InteractionResult.PASS, "and so is a block in hand, got " + withStone);
		helper.succeed();
	}



	/** Spec §2: every server paint state has its own client state, and none of them shows water or nothing. */
	@GameTest
	public void paintStatesAreUniqueAndSafe(GameTestHelper helper) {
		List<BlockState> all = PaintStates.all();
		helper.assertValueEqual(all.size(), PaintColor.values().length * (PaintStates.CONNECTED_PER_COLOR + PaintStates.SPLAT_PER_COLOR), "client states in use");
		helper.assertValueEqual(new HashSet<>(all).size(), all.size(), "client states are distinct");
		for (BlockState state : all) {
			helper.assertTrue(PaintStates.DONORS.contains(state.getBlock()), "a donor block: " + state);
			helper.assertTrue(state.getBlock() != Blocks.GLOW_LICHEN, "glow lichen is not a donor: " + state);
			helper.assertValueEqual(state.getLightEmission(), 0, "unlit: " + state);
			if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
				helper.assertFalse(state.getValue(BlockStateProperties.WATERLOGGED), "never waterlogged: " + state);
			}
			if (state.getBlock() instanceof MultifaceBlock) {
				boolean anyFace = false;
				for (Direction d : Direction.values()) anyFace |= state.getValue(MultifaceBlock.getFaceProperty(d));
				helper.assertTrue(anyFace, "a multiface donor state with no face renders nothing: " + state);
			}
		}
		// The same request always gives the same state, and popcount-1 splat masks fold into connected.
		helper.assertValueEqual(PaintStates.connected(PaintColor.DATA, Direction.UP, 5), PaintStates.connected(PaintColor.DATA, Direction.UP, 5), "deterministic");
		helper.assertValueEqual(PaintStates.splat(PaintColor.IT, 1 << Direction.NORTH.ordinal()), PaintStates.connected(PaintColor.IT, Direction.NORTH, 0), "single-face mask is a connected state");
		// entry() inverts connected()/splat() for every colour, face/bits and every splat mask.
		for (PaintColor color : PaintColor.values()) {
			for (Direction face : Direction.values()) {
				for (int bits = 0; bits < 16; bits++) {
					PaintStates.Entry expected = new PaintStates.Entry(color, face, bits, 1 << face.ordinal());
					helper.assertValueEqual(PaintStates.entry(PaintStates.connected(color, face, bits)), expected, "connected round-trip: " + color + " " + face + " " + bits);
				}
			}
			for (int mask = 1; mask < 64; mask++) {
				if (Integer.bitCount(mask) < 2) continue;
				PaintStates.Entry expected = new PaintStates.Entry(color, null, 0, mask);
				helper.assertValueEqual(PaintStates.entry(PaintStates.splat(color, mask)), expected, "splat round-trip: " + color + " " + mask);
			}
		}
		helper.succeed();
	}

	/**
	 * The outline follows the ink. The client draws the targeted-block highlight from the client
	 * state's own shape, which no resource pack can change, so the allocator picks donor states whose
	 * shape matches the paint: splat masks exactly, floors flat on the floor, walls striped up the
	 * wall they are painted on.
	 */
	@GameTest
	public void paintStatesOutlineTheInk(GameTestHelper helper) {
		// Splats: a multiface donor's shape is the union of 1-px slabs on its set faces, so the face
		// flags have to be the mask itself, bit for bit.
		for (PaintColor color : PaintColor.values()) {
			for (int mask = 1; mask < 64; mask++) {
				if (Integer.bitCount(mask) < 2) continue;
				BlockState client = PaintStates.splat(color, mask);
				helper.assertTrue(client.getBlock() instanceof MultifaceBlock, "splat " + color + " " + mask + " is a multiface donor, not " + client);
				for (Direction d : Direction.values()) {
					boolean painted = (mask & 1 << d.ordinal()) != 0;
					helper.assertValueEqual(client.getValue(MultifaceBlock.getFaceProperty(d)), painted,
							"splat " + color + " mask " + mask + ": the donor's " + d + " flag is the mask's " + d + " bit");
				}
			}
		}
		// Floors: a thin full-square slab lying on the floor of the cell.
		for (PaintColor color : PaintColor.values()) {
			for (int bits = 0; bits < 16; bits++) {
				AABB box = outline(PaintStates.connected(color, Direction.DOWN, bits));
				helper.assertTrue(box.maxY <= 3.0 / 16.0, "floor " + color + " " + bits + " lies on the floor, maxY=" + box.maxY);
				helper.assertTrue(box.minX <= 0.0 && box.maxX >= 1.0 && box.minZ <= 0.0 && box.maxZ >= 1.0,
						"floor " + color + " " + bits + " covers the whole square, " + box);
			}
		}
		// Walls: at least half of each colour's sixteen per direction are the tall strip states that
		// actually climb the painted face. The rest are the flat/half fallbacks the budget forces.
		for (Direction wall : Direction.Plane.HORIZONTAL) {
			for (PaintColor color : PaintColor.values()) {
				int strips = 0;
				for (int bits = 0; bits < 16; bits++) {
					AABB box = outline(PaintStates.connected(color, wall, bits));
					boolean touches = switch (wall) {
						case NORTH -> box.minZ <= 0.0;
						case SOUTH -> box.maxZ >= 1.0;
						case WEST -> box.minX <= 0.0;
						default -> box.maxX >= 1.0;
					};
					if (touches && box.maxY - box.minY >= 15.0 / 16.0) strips++;
				}
				helper.assertTrue(strips >= 8, wall + " paint for " + color + ": " + strips + " of 16 states climb that face, wanted 8");
			}
		}
		helper.succeed();
	}

	/** The box the client would draw round a targeted cell holding this client state. */
	private static AABB outline(BlockState client) {
		return client.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).bounds();
	}

	/**
	 * Every ink burst in the module is made of block crumbs carrying a paint client state, so the client
	 * pulls the sprite off that state's model {@code particle} texture and the crumbs come out in the
	 * team colour. The state has to be one of ours and it has to be multiface: a redstone-wire-backed
	 * state would go through vanilla's colour provider and come out dark red instead.
	 */
	@GameTest
	public void inkCrumbsWearTheTeamColour(GameTestHelper helper) {
		Set<BlockState> paintStates = new HashSet<>(PaintStates.all());
		Set<BlockState> seen = new HashSet<>();
		for (PaintColor color : PaintColor.values()) {
			BlockParticleOption crumbs = Painter.crumbs(color);
			helper.assertValueEqual(crumbs.getType(), ParticleTypes.BLOCK, "a block-break crumb for " + color);
			BlockState state = crumbs.getState();
			helper.assertTrue(paintStates.contains(state), color + " crumbs carry one of our client states, not " + state);
			helper.assertTrue(state.getBlock() instanceof MultifaceBlock, color + " crumbs carry an untinted multiface state, not " + state);
			helper.assertValueEqual(PaintStates.entry(state).color(), color, "and it is that colour's own state");
			helper.assertTrue(seen.add(state), "each colour has its own crumb state");
			helper.assertValueEqual(Painter.crumbs(color), crumbs, "cached per colour");
		}
		helper.succeed();
	}

	/** Spec §4: floor then wall in the same air cell → the multiface fallback with both faces. */
	@GameTest
	public void cornerCellFallsBackToSplat(GameTestHelper helper) {
		BlockPos floor = new BlockPos(2, 1, 2);
		BlockPos wall = new BlockPos(2, 2, 1);
		helper.setBlock(floor, Blocks.STONE);
		helper.setBlock(wall, Blocks.STONE);
		BlockPos cell = new BlockPos(2, 2, 2);
		ServerLevel level = helper.getLevel();
		helper.assertTrue(Painter.paintFace(level, helper.absolutePos(floor), Direction.UP, PaintColor.DATA), "floor painted");
		BlockState single = level.getBlockState(helper.absolutePos(cell));
		helper.assertTrue(single.getBlock() instanceof ConnectedPaintBlock, "one face is a connected cell");
		helper.assertValueEqual(single.getValue(ConnectedPaintBlock.FACE), Direction.DOWN, "floor paint attaches down");
		helper.assertTrue(Painter.paintFace(level, helper.absolutePos(wall), Direction.SOUTH, PaintColor.DATA), "wall painted");
		BlockState corner = level.getBlockState(helper.absolutePos(cell));
		helper.assertTrue(corner.getBlock() instanceof PaintBlock, "two faces fall back to the multiface block");
		helper.assertTrue(corner.getValue(MultifaceBlock.getFaceProperty(Direction.DOWN)), "keeps the floor face");
		helper.assertTrue(corner.getValue(MultifaceBlock.getFaceProperty(Direction.NORTH)), "gains the wall face");
		helper.assertFalse(Painter.paintFace(level, helper.absolutePos(wall), Direction.SOUTH, PaintColor.DATA), "same face again is a no-op");
		helper.assertTrue(Painter.paintFace(level, helper.absolutePos(wall), Direction.SOUTH, PaintColor.IT), "the other colour repaints");
		BlockState over = level.getBlockState(helper.absolutePos(cell));
		helper.assertTrue(over.getBlock() instanceof ConnectedPaintBlock && ((Paint) over.getBlock()).color() == PaintColor.IT, "overpaint wipes the cell to one IT face");
		helper.succeed();
	}

	/**
	 * One face per connected cell, popcount per splat cell — read off the three cells this test paints
	 * rather than off a level-global before/after delta. {@link PaintTally#count} folds in every display
	 * quad in the level, so a delta here answers for whatever else the structure happens to hold; the
	 * face masks of our own cells answer only for us.
	 */
	@GameTest
	public void tallyCountsConnectedAndSplat(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PaintTally tally = new PaintTally();
		for (int x = 1; x <= 3; x++) helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE);
		helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
		for (int x = 1; x <= 3; x++) Painter.paintFace(level, helper.absolutePos(new BlockPos(x, 1, 2)), Direction.UP, PaintColor.DATA);
		Painter.paintFace(level, helper.absolutePos(new BlockPos(2, 2, 1)), Direction.SOUTH, PaintColor.DATA);
		int faces = 0;
		for (int x = 1; x <= 3; x++) {
			BlockPos cell = helper.absolutePos(new BlockPos(x, 2, 2));
			tally.track(cell);
			BlockState state = level.getBlockState(cell);
			helper.assertTrue(state.getBlock() instanceof Paint, "paint at " + cell + ", not " + state);
			Paint paint = (Paint) state.getBlock();
			helper.assertValueEqual(paint.color(), PaintColor.DATA, "DATA paint at " + cell);
			faces += Integer.bitCount(paint.faceMask(state));
		}
		helper.assertValueEqual(faces, 4, "three floor faces plus the wall face on the middle cell");
		helper.assertValueEqual(tally.cells(), 3, "three cells tracked");
		helper.assertTrue(tally.count(level).get(PaintColor.DATA) >= faces, "the tally sees at least our own faces");
		helper.succeed();
	}

	/** Spec §7: a 3×3 floor — centre all four bits, an edge three, a corner two. */
	@GameTest
	public void floorPaintConnects(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
		for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) Painter.paintFace(level, helper.absolutePos(new BlockPos(x, 1, z)), Direction.UP, PaintColor.DATA);
		helper.assertValueEqual(ConnectedPaintBlock.bits(level.getBlockState(helper.absolutePos(new BlockPos(2, 2, 2)))), 15, "centre: all four");
		helper.assertValueEqual(ConnectedPaintBlock.bits(level.getBlockState(helper.absolutePos(new BlockPos(1, 2, 2)))), 0b1110, "west edge: everything but NEG_U (west)");
		helper.assertValueEqual(ConnectedPaintBlock.bits(level.getBlockState(helper.absolutePos(new BlockPos(1, 2, 1)))), 0b1010, "north-west corner: POS_U (east) and POS_V (south)");
		BlockState centre = level.getBlockState(helper.absolutePos(new BlockPos(2, 2, 2)));
		helper.assertValueEqual(((PolymerBlock) centre.getBlock()).getPolymerBlockState(centre, PacketContext.get()), PaintStates.connected(PaintColor.DATA, Direction.DOWN, 15), "the client sees the all-connected state");
		helper.succeed();
	}

	/** A 3-wide, 2-high north wall (paint cells south of it): the bottom-middle cell connects up and sideways, not down. */
	@GameTest
	public void wallPaintUsesTheWorldFrame(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 1; x <= 3; x++) for (int y = 2; y <= 3; y++) helper.setBlock(new BlockPos(x, y, 1), Blocks.STONE);
		for (int x = 1; x <= 3; x++) for (int y = 2; y <= 3; y++) Painter.paintFace(level, helper.absolutePos(new BlockPos(x, y, 1)), Direction.SOUTH, PaintColor.IT);
		BlockState cell = level.getBlockState(helper.absolutePos(new BlockPos(2, 2, 2)));
		helper.assertValueEqual(cell.getValue(ConnectedPaintBlock.FACE), Direction.NORTH, "attaches north");
		helper.assertValueEqual(ConnectedPaintBlock.bits(cell), 0b1011, "NEG_U (west), POS_U (east), POS_V (up); no NEG_V (down)");
		helper.succeed();
	}

	/** IT over the middle of a DATA row: the DATA neighbours drop that bit, the IT cell has none. */
	@GameTest
	public void overpaintReconnects(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 1; x <= 3; x++) helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE);
		for (int x = 1; x <= 3; x++) Painter.paintFace(level, helper.absolutePos(new BlockPos(x, 1, 2)), Direction.UP, PaintColor.DATA);
		helper.assertValueEqual(ConnectedPaintBlock.bits(level.getBlockState(helper.absolutePos(new BlockPos(1, 2, 2)))), 0b0010, "west cell connects east");
		Painter.paintFace(level, helper.absolutePos(new BlockPos(2, 1, 2)), Direction.UP, PaintColor.IT);
		helper.assertValueEqual(ConnectedPaintBlock.bits(level.getBlockState(helper.absolutePos(new BlockPos(1, 2, 2)))), 0, "west cell lost its neighbour");
		helper.assertValueEqual(ConnectedPaintBlock.bits(level.getBlockState(helper.absolutePos(new BlockPos(3, 2, 2)))), 0, "east cell lost its neighbour");
		helper.assertValueEqual(ConnectedPaintBlock.bits(level.getBlockState(helper.absolutePos(new BlockPos(2, 2, 2)))), 0, "the IT cell has no IT neighbours");
		helper.succeed();
	}

	/** Every client state in use has a blockstate variant; every (colour, bits) has a texture; the marker alpha is on every texel. */
	@GameTest
	public void packCoversEveryPaintState(GameTestHelper helper) throws IOException {
		Map<String, byte[]> files = PaintArt.packFiles();
		for (PaintColor color : PaintColor.values()) {
			for (int bits = 0; bits < 16; bits++) {
				byte[] png = files.get("assets/metacraft-rivals/textures/block/" + PaintArt.textureName(color, bits) + ".png");
				helper.assertTrue(png != null, "texture for " + color + " bits " + bits);
				BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
				int argb = image.getRGB(0, 0);
				helper.assertValueEqual(argb >>> 24, PaintArt.PAINT_ALPHA, "marker alpha");
				helper.assertValueEqual((argb >> 16 & 0xFF) & 0x0F, bits, "bits in the red nibble");
				helper.assertValueEqual(image.getRGB(15, 15), argb, "uniform");
			}
		}
		for (Block donor : PaintStates.DONORS) {
			String path = "assets/minecraft/blockstates/" + BuiltInRegistries.BLOCK.getKey(donor).getPath() + ".json";
			helper.assertTrue(files.containsKey(path), "override " + path);
			String json = new String(files.get(path), StandardCharsets.UTF_8);
			for (BlockState state : PaintStates.all()) {
				if (state.getBlock() != donor) continue;
				helper.assertTrue(json.contains("\"" + PaintArt.variantKey(state) + "\""), "variant for " + state);
			}
		}
		helper.succeed();
	}

	/**
	 * Run something with the tuning to itself. Every weapon's every parameter is noted down first and put
	 * back afterwards, whatever happens in between, because the live tuning is one table for the whole
	 * server and the tests in a batch tick side by side: a test that left the shooter at half velocity
	 * would be re-tuning every other test's gun. The body has to be synchronous for the same reason —
	 * nothing here may span a tick, or another test's shot lands inside the window.
	 */
	private static void withTuning(Runnable body) {
		Map<Weapon, Map<Param, Double>> before = new EnumMap<>(Weapon.class);
		for (Weapon weapon : Weapon.values()) {
			Map<Param, Double> values = new EnumMap<>(Param.class);
			for (Param param : Param.values()) values.put(param, WeaponTuning.get(weapon).value(param));
			before.put(weapon, values);
		}
		try {
			body.run();
		} finally {
			for (Weapon weapon : Weapon.values()) {
				before.get(weapon).forEach((param, value) -> WeaponTuning.get(weapon).set(param, value));
			}
		}
	}

	/**
	 * The tuning starts where the weapons were written: every parameter's default is the constant the
	 * fire modes used to read, so turning the tuning on changed nothing about how anything shoots.
	 */
	@GameTest
	public void tuningDefaultsMatchTheEnum(GameTestHelper helper) {
		withTuning(() -> {
			WeaponTuning.resetAll();
			for (Weapon weapon : Weapon.values()) {
				WeaponTuning tuning = WeaponTuning.get(weapon);
				helper.assertValueEqual(tuning.value("velocity"), (double) weapon.velocity, weapon.commandId() + " velocity");
				helper.assertValueEqual(tuning.value("spread"), (double) weapon.inaccuracy, weapon.commandId() + " spread");
				helper.assertValueEqual(tuning.value("ink"), (double) weapon.inkPerShot, weapon.commandId() + " ink");
				helper.assertValueEqual(tuning.value("cooldown"), (double) weapon.cooldownTicks, weapon.commandId() + " cooldown");
				helper.assertValueEqual(tuning.value("kick"), (double) weapon.kickPitch, weapon.commandId() + " kick");
				helper.assertValueEqual(tuning.value("damage"), (double) weapon.damage, weapon.commandId() + " damage");
				helper.assertValueEqual(tuning.value("restitution"), PaintBall.BOUNCE_RESTITUTION, weapon.commandId() + " restitution");
				// A default outside its own bounds would be a number the command could never type back.
				for (Param param : Param.values()) {
					helper.assertTrue(param.holds(tuning.defaultValue(param)),
							weapon.commandId() + " " + param.id + " default " + tuning.defaultValue(param) + " is within " + param.range());
				}
			}
			// The ball weapons: the numbers each arm of fire used to spell out for itself.
			helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("bounces"), (double) Weapon.SHOOTER_BOUNCES, "shooter bounces");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("count"), 1.0, "shooter fires one ball");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("gravity"), PaintBall.GRAVITY, "shooter gravity");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("splat_radius"), (double) Painter.RADIUS, "shooter splat radius");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SPRAYER).value("count"), (double) Weapon.SPRAYER_DROPLETS, "sprayer droplets");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SPRAYER).value("lifetime"), (double) Weapon.SPRAYER_LIFETIME, "sprayer lifetime");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SPRAYER).value("splat_radius"), 0.0, "a droplet paints a single face");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SLOSHER).value("count"), (double) Weapon.SLOSHER_FAN.length, "slosher balls");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SLOSHER).value("gravity"), Weapon.SLOSHER_GRAVITY, "slosher gravity");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SLOSHER).value("fan_pitch"), (double) Weapon.SLOSHER_PITCH, "slosher lob");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SLOSHER).value("splat_radius"), (double) Weapon.SLOSHER_SPLAT_RADIUS, "slosher 5x5");
			// fan_yaw is the hand-written fan {-15, -5, 5, 15} as one number: four balls, ten degrees apart.
			double step = WeaponTuning.get(Weapon.SLOSHER).value("fan_yaw");
			for (int i = 0; i < Weapon.SLOSHER_FAN.length; i++) {
				helper.assertValueEqual((double) Weapon.SLOSHER_FAN[i], (i - (Weapon.SLOSHER_FAN.length - 1) / 2.0) * step,
						"slosher fan offset " + i);
			}
			// And the charger's own, which no other weapon reads.
			WeaponTuning charger = WeaponTuning.get(Weapon.CHARGER);
			helper.assertValueEqual(charger.value("charge_min"), (double) Weapon.MIN_CHARGE_TICKS, "charger minimum charge");
			helper.assertValueEqual(charger.value("charge_full"), (double) Weapon.CHARGE_FULL_TICKS, "charger full charge");
			helper.assertValueEqual(charger.value("range_min"), Weapon.CHARGE_BASE_RANGE, "charger range at no charge");
			helper.assertValueEqual(charger.value("range_full"), Weapon.CHARGE_BASE_RANGE + Weapon.CHARGE_EXTRA_RANGE,
					"charger range at a full charge");
			helper.assertValueEqual(charger.value("charge_ink_full"), (double) (Weapon.CHARGE_BASE_COST + Weapon.CHARGE_EXTRA_COST),
					"charger ink at a full charge");
			helper.assertValueEqual(charger.value("charge_damage_full"), (double) (Weapon.CHARGE_BASE_DAMAGE + Weapon.CHARGE_EXTRA_DAMAGE),
					"charger damage at a full charge");
			helper.assertFalse(WeaponTuning.applies(Weapon.SLOSHER, Param.RANGE_FULL), "the slosher has no charge to tune");
			helper.assertFalse(WeaponTuning.applies(Weapon.CHARGER, Param.BOUNCES), "the charger throws nothing to bounce");
		});
		helper.succeed();
	}

	/** A tuned number is in the next shot: no restart, no re-registering, just the next click. */
	@GameTest
	public void tuningChangesReachTheShot(GameTestHelper helper) {
		Player player = gunner(helper);
		helper.getLevel().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team(helper, PaintColor.DATA));
		withTuning(() -> {
			WeaponTuning shooter = WeaponTuning.get(Weapon.SHOOTER);
			shooter.reset();
			shooter.set(Param.VELOCITY, 0.5);
			shooter.set(Param.BOUNCES, 0.0);
			PaintBall slow = onlyBall(helper, player);
			// The spread is still on, and vanilla adds its jitter to the unit direction before scaling by the
			// velocity, so the speed is the tuned one give or take a few per cent — not give or take 1.3.
			helper.assertTrue(Math.abs(slow.getDeltaMovement().length() - 0.5) < 0.06,
					"the tuned velocity is the shot's: " + slow.getDeltaMovement().length());
			helper.assertValueEqual(slow.bouncesLeft(), 0, "the tuned bounces are the ball's");
			slow.discard();
			shooter.reset();
			PaintBall fast = onlyBall(helper, player);
			helper.assertTrue(Math.abs(fast.getDeltaMovement().length() - Weapon.SHOOTER.velocity) < 0.15,
					"a reset puts the default velocity back: " + fast.getDeltaMovement().length());
			helper.assertValueEqual(fast.bouncesLeft(), Weapon.SHOOTER_BOUNCES, "and the default bounces");
			fast.discard();
			// A splat radius is the side of a loop and a count is a spawn, so neither takes a number that
			// would turn one click into a million block writes or five thousand entities.
			refused(helper, () -> shooter.set(Param.SPLAT_RADIUS, 500.0), "a splat radius of 500");
			refused(helper, () -> shooter.set(Param.COUNT, 5000.0), "a count of 5000");
			refused(helper, () -> shooter.set(Param.VELOCITY, Double.NaN), "a velocity of NaN");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("splat_radius"), (double) Painter.RADIUS,
					"a refused set leaves the value alone");
		});
		helper.succeed();
	}

	/** Something the tuning must not accept: {@code set} throws rather than taking it. */
	private static void refused(GameTestHelper helper, Runnable set, String what) {
		try {
			set.run();
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw helper.assertionException(Component.literal(what + " was accepted"));
	}

	/** One click of the shooter, and the ball it threw. */
	private static PaintBall onlyBall(GameTestHelper helper, Player player) {
		InteractionResult result = PaintWeapon.of(Weapon.SHOOTER).use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(result.consumesAction(), "shoots");
		List<PaintBall> balls = helper.getEntities(PaintBall.TYPE, new BlockPos(4, 3, 4), 4.0);
		helper.assertValueEqual(balls.size(), 1, "one ball");
		return balls.get(0);
	}

	/**
	 * The file keeps what has been tuned and nothing else, and reading it back puts exactly that on top
	 * of the defaults — so a default that moves in the code moves for everyone who never touched it. A
	 * file that has been edited by hand is read defensively: nothing in it can put a value somewhere the
	 * command would not have let it go.
	 */
	@GameTest
	public void tuningRoundTripsThroughJson(GameTestHelper helper) throws IOException {
		Path file = Files.createTempFile("rivals-weapons", ".json");
		try {
			withTuningChecked(() -> {
				WeaponTuning.resetAll();
				WeaponTuning.get(Weapon.SHOOTER).set(Param.VELOCITY, 0.5);
				WeaponTuning.get(Weapon.SLOSHER).set(Param.GRAVITY, 0.2);
				WeaponTuning.save(file);
				WeaponTuning.resetAll();
				helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("velocity"), (double) Weapon.SHOOTER.velocity,
						"a reset instance is back at the defaults before the load");
				WeaponTuning.load(file);
				helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("velocity"), 0.5, "the shooter's velocity came back");
				helper.assertValueEqual(WeaponTuning.get(Weapon.SLOSHER).value("gravity"), 0.2, "the slosher's gravity came back");
				helper.assertValueEqual(WeaponTuning.get(Weapon.SPRAYER).value("velocity"), (double) Weapon.SPRAYER.velocity,
						"a weapon nobody tuned is untouched by the file");
				JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				helper.assertValueEqual(root.keySet(), Set.of("shooter", "slosher"), "only the two tuned weapons are in the file");
				helper.assertValueEqual(root.getAsJsonObject("shooter").keySet(), Set.of("velocity"), "and only the one key");
				helper.assertValueEqual(root.getAsJsonObject("slosher").keySet(), Set.of("gravity"), "and only the one key");
				// Nothing tuned is an empty object, not a full dump of every default.
				WeaponTuning.resetAll();
				WeaponTuning.save(file);
				helper.assertValueEqual(Files.readString(file, StandardCharsets.UTF_8).trim(), "{}", "a fresh tuning writes {}");
				// Now the file as a hand edit can leave it: Gson's parser is lenient enough to hand back NaN,
				// 500 is outside what a splat radius may be, and range_full is a number a shooter never reads.
				Files.writeString(file, "{\"shooter\": {\"velocity\": NaN, \"splat_radius\": 500, \"range_full\": 99},"
						+ " \"nonesuch\": {\"velocity\": 1}}", StandardCharsets.UTF_8);
				WeaponTuning.load(file);
				helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("velocity"), (double) Weapon.SHOOTER.velocity,
						"NaN is not a velocity: the default stands");
				helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("splat_radius"), Param.SPLAT_RADIUS.max,
						"an out-of-range splat radius is clamped, not taken");
				helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("range_full"),
						WeaponTuning.get(Weapon.SHOOTER).defaultValue(Param.RANGE_FULL),
						"a parameter the shooter does not read is ignored");
			});
		} finally {
			Files.deleteIfExists(file);
		}
		helper.succeed();
	}

	/** {@link #withTuning} for a body that may throw a checked exception. */
	private static void withTuningChecked(IOBody body) throws IOException {
		IOException[] thrown = new IOException[1];
		withTuning(() -> {
			try {
				body.run();
			} catch (IOException failure) {
				thrown[0] = failure;
			}
		});
		if (thrown[0] != null) throw thrown[0];
	}

	private interface IOBody {
		void run() throws IOException;
	}

	/**
	 * A parameter nobody has heard of is a failure that says what the weapon does have, rather than a
	 * silent no-op: the whole point of the command is that you can find the knobs from inside the game.
	 * A number outside what the parameter takes is the same story, with the range in the message.
	 */
	@GameTest
	public void tuneCommandRejectsUnknownParameters(GameTestHelper helper) {
		List<String> said = new ArrayList<>();
		CommandSource sink = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				said.add(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false; // nothing here is worth telling the whole server about
			}
		};
		MinecraftServer server = helper.getLevel().getServer();
		CommandSourceStack source = server.createCommandSourceStack().withSource(sink);
		withTuning(() -> {
			WeaponTuning.resetAll();
			server.getCommands().performPrefixedCommand(source, "rivals tune shooter nope 1");
			String text = String.join(" | ", said);
			helper.assertTrue(text.contains("nope"), "the failure names what was typed: " + text);
			helper.assertTrue(text.contains("velocity") && text.contains("bounces") && text.contains("splat_radius"),
					"the failure lists the names that would have worked: " + text);
			helper.assertFalse(text.contains("range_full"), "and not the charger's, on a shooter: " + text);
			helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("velocity"), (double) Weapon.SHOOTER.velocity,
					"a refused set changed nothing");
			// A known name with a number it cannot take is refused too, and the message says what it can.
			said.clear();
			server.getCommands().performPrefixedCommand(source, "rivals tune shooter splat_radius 500");
			String refused = String.join(" | ", said);
			helper.assertTrue(refused.contains("splat_radius") && refused.contains(Param.SPLAT_RADIUS.range()),
					"the failure states the range: " + refused);
			helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("splat_radius"), (double) Painter.RADIUS,
					"an out-of-range set changed nothing");
			// The same command with a name and a number that both work does land. Deliberately the kick,
			// which nothing about a ball in flight reads, since other tests are firing while this runs.
			said.clear();
			server.getCommands().performPrefixedCommand(source, "rivals tune shooter kick -4");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("kick"), -4.0, "a known parameter is set");
			helper.assertTrue(String.join(" | ", said).contains("-4"), "and the reply says so: " + said);
			server.getCommands().performPrefixedCommand(source, "rivals tune reset");
			helper.assertValueEqual(WeaponTuning.get(Weapon.SHOOTER).value("kick"), (double) Weapon.SHOOTER.kickPitch,
					"/rivals tune reset puts every weapon back");
		});
		helper.succeed();
	}
}
