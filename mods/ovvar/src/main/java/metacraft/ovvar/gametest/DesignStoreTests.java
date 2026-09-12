package metacraft.ovvar.gametest;

import metacraft.ovvar.OvvarConfig;
import metacraft.ovvar.content.Chapter;
import metacraft.ovvar.content.Looks;
import metacraft.ovvar.content.ModContent;
import metacraft.ovvar.content.OvveItem;
import metacraft.ovvar.content.Patches;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.Spot;
import metacraft.ovvar.content.SpotPlacements;
import metacraft.ovvar.store.Design;
import metacraft.ovvar.store.DesignBackend;
import metacraft.ovvar.store.DesignKey;
import metacraft.ovvar.store.DesignStoreConfig;
import metacraft.ovvar.store.Designs;
import metacraft.ovvar.store.FileBackend;
import metacraft.ovvar.store.JdbcBackend;
import metacraft.ovvar.store.OwnedSewing;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The design store: both backends refuse a write that names the wrong version, the cache in front
 * of them turns a lost race into a refetch, two ovves of one owner are one design, and an unpick
 * hands the patch out once no matter how many ovves show it. The store tests swap the server's
 * backend for a temporary one and put the configured one back at the end; as that is global
 * state and tests in a batch run together, they take turns ({@link #BUSY}).
 */
public final class DesignStoreTests {
	/** Held by whichever sequence test is using the server's design store right now. */
	private static final AtomicBoolean BUSY = new AtomicBoolean();

	private static final Chapter CHAPTER = Chapter.values()[0];
	private static final Placement BEER = new Placement(Spot.FRONT_TOP_LEFT, Patches.get("beer"));
	private static final Placement HEART = new Placement(Spot.BACK_TOP_RIGHT, Patches.get("heart"));

	@GameTest
	public void fileBackendStoresWithVersions(GameTestHelper helper) throws IOException {
		storesWithVersions(helper, new FileBackend(Files.createTempDirectory("ovvar-designs")));
	}

	@GameTest
	public void jdbcBackendStoresWithVersions(GameTestHelper helper) throws IOException {
		storesWithVersions(helper, new JdbcBackend(h2()));
	}

	private static DesignStoreConfig.Jdbc h2() {
		return new DesignStoreConfig.Jdbc("jdbc:h2:mem:ovvar_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1",
				"sa", "", "", "ovve_designs", "org.h2.Driver", 5, 5);
	}

	private static void storesWithVersions(GameTestHelper helper, DesignBackend backend) throws IOException {
		UUID owner = UUID.randomUUID();
		DesignKey key = new DesignKey(owner, CHAPTER);
		if (!backend.loadAll(owner).isEmpty()) helper.fail("a fresh store has designs");
		Design v1 = new Design(SpotPlacements.fromList(List.of(BEER)).getOrThrow(), 1);
		if (!backend.store(key, v1, 0)) helper.fail("first write refused");
		if (backend.store(key, v1, 0)) helper.fail("a second insert of the same key went through");
		Design v2 = new Design(SpotPlacements.fromList(List.of(BEER, HEART)).getOrThrow(), 2);
		if (!backend.store(key, v2, 1)) helper.fail("update from version 1 refused");
		if (backend.store(key, v2.withVersion(3), 1)) helper.fail("update from a stale version went through");
		Map<Chapter, Design> loaded = backend.loadAll(owner);
		if (!v2.equals(loaded.get(CHAPTER))) helper.fail("loaded " + loaded + ", wanted " + v2);
		Design empty = new Design(null, 3);
		if (!backend.store(key, empty, 2)) helper.fail("writing an empty design refused");
		if (!empty.equals(backend.loadAll(owner).get(CHAPTER))) helper.fail("empty design did not round-trip");
		backend.close();
		helper.succeed();
	}

	@GameTest(maxTicks = 1200)
	public void designsUpdateIsCompareAndSet(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-designs");
		FileBackend behind = new FileBackend(dir);   // the same files, written "from another server"
		UUID owner = UUID.randomUUID();
		DesignKey key = new DesignKey(owner, CHAPTER);
		AtomicReference<Designs.Outcome> outcome = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Designs.use(server, new FileBackend(dir));
					Designs.fetch(owner);
				})
				.thenWaitUntil(() -> assertThat(Designs.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Designs.update(key, d -> d.sew(BEER), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Designs.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					if (Designs.current(key).version() != 1) helper.fail("cached version " + Designs.current(key).version() + ", wanted 1");
					// Another server unpicks and sews twice more: the store is at version 5 with only the heart.
					try {
						if (!behind.store(key, new Design(SpotPlacements.fromList(List.of(HEART)).getOrThrow(), 5), 1)) helper.fail("behind-the-back write refused");
					} catch (IOException e) {
						throw new GameTestAssertException(Component.literal(e.toString()), 0);
					}
					outcome.set(null);
					Designs.update(key, d -> d.unpick(Spot.FRONT_TOP_LEFT), outcome::set);
				})
				.thenWaitUntil(() -> assertThat(outcome.get() == Designs.Outcome.CONFLICT, "stale write outcome " + outcome.get()))
				.thenWaitUntil(() -> assertThat(Designs.loaded(owner) && Designs.current(key).version() == 5, "cache not refetched to version 5"))
				.thenExecute(() -> {
					if (Designs.current(key).at(Spot.FRONT_TOP_LEFT).isPresent()) helper.fail("the beer survived the refetch");
					outcome.set(null);
					Designs.update(key, d -> d.sew(BEER), outcome::set);
				})
				.thenWaitUntil(() -> assertThat(outcome.get() == Designs.Outcome.OK && Designs.current(key).version() == 6, "retry after the refetch: " + outcome.get()))
				.thenExecute(() -> release(server))
				.thenSucceed();
	}

	@GameTest(maxTicks = 1200)
	public void twoOvvesShareOneDesign(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-designs");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DesignKey key = new DesignKey(player.getUUID(), CHAPTER);
		ItemStack a = new ItemStack(ModContent.ovve(CHAPTER)), b = new ItemStack(ModContent.ovve(CHAPTER));
		AtomicReference<Designs.Outcome> outcome = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Designs.use(server, new FileBackend(dir));
					// Their first tick in a player's inventory binds them; nothing to adopt, both are plain.
					OvveItem.syncDesign(player, a);
					OvveItem.syncDesign(player, b);
					if (!player.getUUID().equals(OvveItem.owner(a)) || !player.getUUID().equals(OvveItem.owner(b))) helper.fail("not bound on pickup");
				})
				.thenWaitUntil(() -> assertThat(Designs.loaded(player.getUUID()), "owner not loaded"))
				.thenExecute(() -> Designs.update(key, d -> d.sew(BEER), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Designs.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					OvveItem.syncDesign(player, a);
					OvveItem.syncDesign(player, b);
					if (!BEER.equals(Looks.at(a, Spot.FRONT_TOP_LEFT)) || !BEER.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("the sew did not reach both ovves");
					outcome.set(null);
					Designs.update(key, d -> d.unpick(Spot.FRONT_TOP_LEFT), outcome::set);
				})
				.thenWaitUntil(() -> assertThat(outcome.get() == Designs.Outcome.OK, "unpick outcome " + outcome.get()))
				.thenExecute(() -> {
					OvveItem.syncDesign(player, a);
					OvveItem.syncDesign(player, b);
					if (Looks.at(a, Spot.FRONT_TOP_LEFT) != null || Looks.at(b, Spot.FRONT_TOP_LEFT) != null) helper.fail("the unpick did not reach both ovves");
					release(server);
				})
				.thenSucceed();
	}

	/** The dupe rule: with the patch showing on two ovves, unpicking it on the second gives nothing. */
	@GameTest(maxTicks = 1200)
	public void unpickGivesThePatchOnceAcrossOvves(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-designs");
		UUID owner = UUID.randomUUID();
		DesignKey key = new DesignKey(owner, CHAPTER);
		ItemStack a = new ItemStack(ModContent.ovve(CHAPTER)), b = new ItemStack(ModContent.ovve(CHAPTER));
		OvveItem.setOwner(a, owner);
		OvveItem.setOwner(b, owner);
		List<Placement> given = new ArrayList<>();
		List<String> refused = new ArrayList<>();
		AtomicReference<Designs.Outcome> outcome = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Designs.use(server, new FileBackend(dir));
					Designs.fetch(owner);
				})
				.thenWaitUntil(() -> assertThat(Designs.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Designs.update(key, d -> d.sew(BEER), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Designs.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					OvveItem.refresh(a);
					OvveItem.refresh(b);
					if (!BEER.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("b does not show the beer");
					OwnedSewing.unpick(a, Spot.FRONT_TOP_LEFT, given::add, refused::add);
				})
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 1, "first unpick not answered"))
				.thenExecute(() -> {
					if (!given.equals(List.of(BEER))) helper.fail("first unpick: given " + given + ", refused " + refused);
					// b still carries the old copy; the unpick asks the store, not the item.
					if (!BEER.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("b was refreshed before being asked");
					OwnedSewing.unpick(b, Spot.FRONT_TOP_LEFT, given::add, refused::add);
				})
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 2, "second unpick not answered"))
				.thenExecute(() -> {
					if (given.size() != 1 || refused.size() != 1) helper.fail("second unpick: given " + given + ", refused " + refused);
					release(server);
				})
				.thenSucceed();
	}

	/** The configured store back, and the next test may go. */
	private static void release(MinecraftServer server) {
		Designs.open(server, OvvarConfig.get().designs());
		BUSY.set(false);
	}

	private static void assertThat(boolean condition, String message) {
		if (!condition) throw new GameTestAssertException(Component.literal(message), 0);
	}
}
