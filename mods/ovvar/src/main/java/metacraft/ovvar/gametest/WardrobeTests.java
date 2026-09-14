package metacraft.ovvar.gametest;

import metacraft.ovvar.OvvarConfig;
import metacraft.ovvar.content.Chapter;
import metacraft.ovvar.content.Looks;
import metacraft.ovvar.content.ModContent;
import metacraft.ovvar.content.OvveItem;
import metacraft.ovvar.content.Patches;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.Spot;
import metacraft.ovvar.store.DesignStoreConfig;
import metacraft.ovvar.store.FileBackend;
import metacraft.ovvar.store.JdbcBackend;
import metacraft.ovvar.store.OwnedSewing;
import metacraft.ovvar.store.Wardrobe;
import metacraft.ovvar.store.WardrobeBackend;
import metacraft.ovvar.store.Wardrobes;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The wardrobe store: both backends refuse a write that names the wrong version, the cache in
 * front of them turns a lost race into a refetch, a patch moves between the stash and the design
 * and never multiplies, two ovves of one owner are one design, and an unpick hands the patch out
 * once no matter how many ovves show it. The tests that swap the server's backend for a temporary
 * one take turns ({@link #BUSY}: game tests in a batch run together) and put the configured one back.
 */
public final class WardrobeTests {
	/** Held by whichever sequence test is using the server's wardrobe store right now. */
	private static final AtomicBoolean BUSY = new AtomicBoolean();

	private static final Chapter CHAPTER = Chapter.values()[0];
	private static final Patches.Patch ITK_PATCH = Patches.get("itk"), NYCKELN_PATCH = Patches.get("nyckeln");
	private static final Placement ITK = new Placement(Spot.FRONT_TOP_LEFT, ITK_PATCH);
	private static final Placement NYCKELN = new Placement(Spot.BACK_TOP_RIGHT, NYCKELN_PATCH);

	// ---- the record

	@GameTest
	public void patchesMoveBetweenStashAndDesign(GameTestHelper helper) {
		Wardrobe none = Wardrobe.NONE;
		if (none.sew(CHAPTER, ITK).isPresent()) helper.fail("sewn a patch that is not in the stash");
		Wardrobe one = none.add(ITK_PATCH, 1);
		if (one.count(ITK_PATCH) != 1) helper.fail("count after add: " + one.count(ITK_PATCH));
		Wardrobe sewn = one.sew(CHAPTER, ITK).orElseThrow();
		if (sewn.count(ITK_PATCH) != 0) helper.fail("the stash still holds the sewn patch");
		if (!ITK.equals(sewn.at(CHAPTER, Spot.FRONT_TOP_LEFT).orElse(null))) helper.fail("the patch is not on the design");
		if (sewn.sew(CHAPTER, ITK).isPresent()) helper.fail("sewn the same patch twice from an empty stash");
		if (sewn.add(ITK_PATCH, 1).sew(CHAPTER, ITK).isPresent()) helper.fail("sewn over an occupied spot");
		Wardrobe back = sewn.unpick(CHAPTER, Spot.FRONT_TOP_LEFT).orElseThrow();
		if (back.count(ITK_PATCH) != 1 || back.design(CHAPTER).isPresent()) helper.fail("unpick did not move the patch back: " + back);
		if (back.unpick(CHAPTER, Spot.FRONT_TOP_LEFT).isPresent()) helper.fail("unpicked an empty spot");
		if (!back.sameContents(one)) helper.fail("a sew and an unpick do not cancel out: " + back + " vs " + one);
		helper.succeed();
	}

	// ---- the backends

	@GameTest
	public void fileBackendStoresWithVersions(GameTestHelper helper) throws IOException {
		storesWithVersions(helper, new FileBackend(Files.createTempDirectory("ovvar-wardrobes")));
	}

	@GameTest
	public void jdbcBackendStoresWithVersions(GameTestHelper helper) throws IOException {
		storesWithVersions(helper, new JdbcBackend(h2()));
	}

	/**
	 * The same against a real database when one is named: {@code -Dovvar.test.jdbc.url=jdbc:postgresql://host/db}
	 * (plus {@code ovvar.test.jdbc.user} / {@code .password}); passes trivially otherwise.
	 */
	@GameTest
	public void realDatabaseStoresWithVersions(GameTestHelper helper) throws IOException {
		String url = System.getProperty("ovvar.test.jdbc.url");
		if (url == null) {
			helper.succeed();
			return;
		}
		storesWithVersions(helper, new JdbcBackend(new DesignStoreConfig.Jdbc(url, System.getProperty("ovvar.test.jdbc.user", ""),
				System.getProperty("ovvar.test.jdbc.password", ""), "", "ovve_wardrobes_test_" + Long.toHexString(System.nanoTime()), "", 5, 5)));
	}

	private static DesignStoreConfig.Jdbc h2() {
		return new DesignStoreConfig.Jdbc("jdbc:h2:mem:ovvar_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1",
				"sa", "", "", "ovve_wardrobes", "org.h2.Driver", 5, 5);
	}

	private static void storesWithVersions(GameTestHelper helper, WardrobeBackend backend) throws IOException {
		UUID owner = UUID.randomUUID();
		if (backend.load(owner).isPresent()) helper.fail("a fresh store has a wardrobe");
		Wardrobe v1 = Wardrobe.NONE.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElseThrow().withVersion(1);
		if (!backend.store(owner, v1, 0)) helper.fail("first write refused");
		if (backend.store(owner, v1, 0)) helper.fail("a second insert of the same owner went through");
		Wardrobe v2 = v1.add(NYCKELN_PATCH, 3).sew(CHAPTER, NYCKELN).orElseThrow().withVersion(2);
		if (!backend.store(owner, v2, 1)) helper.fail("update from version 1 refused");
		if (backend.store(owner, v2.withVersion(3), 1)) helper.fail("update from a stale version went through");
		Optional<Wardrobe> loaded = backend.load(owner);
		if (!v2.equals(loaded.orElse(null))) helper.fail("loaded " + loaded + ", wanted " + v2);
		Wardrobe empty = Wardrobe.NONE.withVersion(3);
		if (!backend.store(owner, empty, 2)) helper.fail("writing an empty wardrobe refused");
		if (!empty.equals(backend.load(owner).orElse(null))) helper.fail("empty wardrobe did not round-trip");
		backend.close();
		helper.succeed();
	}

	// ---- the cache

	@GameTest(maxTicks = 1200)
	public void wardrobesUpdateIsCompareAndSet(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		FileBackend behind = new FileBackend(dir);   // the same files, written "from another server"
		UUID owner = UUID.randomUUID();
		AtomicReference<Wardrobes.Outcome> outcome = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				})
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					if (Wardrobes.current(owner).version() != 1) helper.fail("cached version " + Wardrobes.current(owner).version() + ", wanted 1");
					// Another server unpicks and sews on: the store is at version 5 with only the nyckeln on.
					try {
						Wardrobe theirs = Wardrobe.NONE.add(ITK_PATCH, 1).add(NYCKELN_PATCH, 1).sew(CHAPTER, NYCKELN).orElseThrow().withVersion(5);
						if (!behind.store(owner, theirs, 1)) helper.fail("behind-the-back write refused");
					} catch (IOException e) {
						throw new GameTestAssertException(Component.literal(e.toString()), 0);
					}
					outcome.set(null);
					Wardrobes.update(owner, w -> w.unpick(CHAPTER, Spot.FRONT_TOP_LEFT).orElse(null), outcome::set);
				})
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.CONFLICT, "stale write outcome " + outcome.get()))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner) && Wardrobes.current(owner).version() == 5, "cache not refetched to version 5"))
				.thenExecute(() -> {
					if (Wardrobes.current(owner).at(CHAPTER, Spot.FRONT_TOP_LEFT).isPresent()) helper.fail("the itk survived the refetch");
					if (Wardrobes.current(owner).count(ITK_PATCH) != 1) helper.fail("the itk is not back in the stash after the refetch");
					outcome.set(null);
					Wardrobes.update(owner, w -> w.sew(CHAPTER, ITK).orElse(null), outcome::set);
				})
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK && Wardrobes.current(owner).version() == 6, "retry after the refetch: " + outcome.get()))
				.thenExecute(() -> release(server))
				.thenSucceed();
	}

	@GameTest(maxTicks = 1200)
	public void twoOvvesShareOneDesign(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		UUID owner = player.getUUID();
		ItemStack a = new ItemStack(ModContent.ovve(CHAPTER)), b = new ItemStack(ModContent.ovve(CHAPTER));
		AtomicReference<Wardrobes.Outcome> outcome = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Wardrobes.use(server, new FileBackend(dir));
					// Their first tick in a player's inventory binds them; nothing to adopt, both are plain.
					OvveItem.syncDesign(player, a);
					OvveItem.syncDesign(player, b);
					if (!owner.equals(OvveItem.owner(a)) || !owner.equals(OvveItem.owner(b))) helper.fail("not bound on pickup");
				})
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					OvveItem.syncDesign(player, a);
					OvveItem.syncDesign(player, b);
					if (!ITK.equals(Looks.at(a, Spot.FRONT_TOP_LEFT)) || !ITK.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("the sew did not reach both ovves");
					outcome.set(null);
					Wardrobes.update(owner, w -> w.unpick(CHAPTER, Spot.FRONT_TOP_LEFT).orElse(null), outcome::set);
				})
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "unpick outcome " + outcome.get()))
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
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		UUID owner = UUID.randomUUID();
		ItemStack a = new ItemStack(ModContent.ovve(CHAPTER)), b = new ItemStack(ModContent.ovve(CHAPTER));
		OvveItem.setOwner(a, owner);
		OvveItem.setOwner(b, owner);
		List<OwnedSewing.Unpicked> given = new ArrayList<>();
		List<String> refused = new ArrayList<>();
		AtomicReference<Wardrobes.Outcome> outcome = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				})
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					OvveItem.refresh(a);
					OvveItem.refresh(b);
					if (!ITK.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("b does not show the itk");
					// Into the hand (a survival server): the store lets go of it first, and only once.
					OwnedSewing.unpick(a, Spot.FRONT_TOP_LEFT, false, given::add, refused::add);
				})
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 1, "first unpick not answered"))
				.thenExecute(() -> {
					if (given.size() != 1 || !ITK.equals(given.get(0).placement()) || given.get(0).toStash()) helper.fail("first unpick: given " + given + ", refused " + refused);
					if (Wardrobes.current(owner).count(ITK_PATCH) != 0) helper.fail("an unpick into the hand also left one in the stash");
					// b still carries the old copy; the unpick asks the store, not the item.
					if (!ITK.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("b was refreshed before being asked");
					OwnedSewing.unpick(b, Spot.FRONT_TOP_LEFT, false, given::add, refused::add);
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
		Wardrobes.open(server, OvvarConfig.get().designs());
		BUSY.set(false);
	}

	private static void assertThat(boolean condition, String message) {
		if (!condition) throw new GameTestAssertException(Component.literal(message), 0);
	}
}
