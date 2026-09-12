package metacraft.ovvar.gametest;

import metacraft.ovvar.Motd;
import metacraft.ovvar.OvvarConfig;
import metacraft.ovvar.ServerConfig;
import metacraft.ovvar.content.Chapter;
import metacraft.ovvar.content.Looks;
import metacraft.ovvar.content.ModContent;
import metacraft.ovvar.content.OvveItem;
import metacraft.ovvar.content.Ownership;
import metacraft.ovvar.content.Patches;
import metacraft.ovvar.content.Placement;
import metacraft.ovvar.content.Spot;
import metacraft.ovvar.store.DesignStoreConfig;
import metacraft.ovvar.store.FileBackend;
import metacraft.ovvar.store.JdbcBackend;
import metacraft.ovvar.store.OwnedSewing;
import metacraft.ovvar.store.StashConfig;
import metacraft.ovvar.store.Wardrobe;
import metacraft.ovvar.store.WardrobeBackend;
import metacraft.ovvar.store.Wardrobes;
import metacraft.ovvar.content.Piece;
import metacraft.ovvar.pack.WardrobeArt;
import metacraft.ovvar.sewing.WardrobeGui;
import metacraft.ovvar.sewing.WardrobeMannequin;
import metacraft.ovvar.sewing.StashSession;
import eu.pb4.sgui.api.elements.GuiElement;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The wardrobe store: both backends refuse a write that names the wrong version, the cache in
 * front of them turns a lost race into a refetch, a patch moves between the stash and the design
 * and never multiplies, two ovves of one owner are one design, and an unpick hands the patch out
 * once no matter how many ovves show it; and the ownership rules: somebody else's ovve is not worn,
 * sewn on or unpicked, the owner's own is, and the MOTD says which server this is. The tests that swap the server's backend for a temporary
 * one take turns ({@link #BUSY}: game tests in a batch run together) and put the configured one back.
 */
public final class WardrobeTests {
	/** Held by whichever sequence test is using the server's wardrobe store right now. */
	private static final AtomicBoolean BUSY = new AtomicBoolean();

	private static final Chapter CHAPTER = Chapter.values()[0];
	private static final Patches.Patch BEER_PATCH = Patches.get("beer"), HEART_PATCH = Patches.get("heart");
	private static final Placement BEER = new Placement(Spot.FRONT_TOP_LEFT, BEER_PATCH);
	private static final Placement HEART = new Placement(Spot.BACK_TOP_RIGHT, HEART_PATCH);

	// ---- the record

	@GameTest
	public void patchesMoveBetweenStashAndDesign(GameTestHelper helper) {
		Wardrobe none = Wardrobe.NONE;
		if (none.sew(CHAPTER, BEER).isPresent()) helper.fail("sewn a patch that is not in the stash");
		Wardrobe one = none.add(BEER_PATCH, 1);
		if (one.count(BEER_PATCH) != 1) helper.fail("count after add: " + one.count(BEER_PATCH));
		Wardrobe sewn = one.sew(CHAPTER, BEER).orElseThrow();
		if (sewn.count(BEER_PATCH) != 0) helper.fail("the stash still holds the sewn patch");
		if (!BEER.equals(sewn.at(CHAPTER, Spot.FRONT_TOP_LEFT).orElse(null))) helper.fail("the patch is not on the design");
		if (sewn.sew(CHAPTER, BEER).isPresent()) helper.fail("sewn the same patch twice from an empty stash");
		if (sewn.add(BEER_PATCH, 1).sew(CHAPTER, BEER).isPresent()) helper.fail("sewn over an occupied spot");
		Wardrobe back = sewn.unpick(CHAPTER, Spot.FRONT_TOP_LEFT).orElseThrow();
		if (back.count(BEER_PATCH) != 1 || back.design(CHAPTER).isPresent()) helper.fail("unpick did not move the patch back: " + back);
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
		Wardrobe v1 = Wardrobe.NONE.add(BEER_PATCH, 1).sew(CHAPTER, BEER).orElseThrow().withVersion(1);
		if (!backend.store(owner, v1, 0)) helper.fail("first write refused");
		if (backend.store(owner, v1, 0)) helper.fail("a second insert of the same owner went through");
		Wardrobe v2 = v1.add(HEART_PATCH, 3).sew(CHAPTER, HEART).orElseThrow().withVersion(2);
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
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(BEER_PATCH, 1).sew(CHAPTER, BEER).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					if (Wardrobes.current(owner).version() != 1) helper.fail("cached version " + Wardrobes.current(owner).version() + ", wanted 1");
					// Another server unpicks and sews on: the store is at version 5 with only the heart on.
					try {
						Wardrobe theirs = Wardrobe.NONE.add(BEER_PATCH, 1).add(HEART_PATCH, 1).sew(CHAPTER, HEART).orElseThrow().withVersion(5);
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
					if (Wardrobes.current(owner).at(CHAPTER, Spot.FRONT_TOP_LEFT).isPresent()) helper.fail("the beer survived the refetch");
					if (Wardrobes.current(owner).count(BEER_PATCH) != 1) helper.fail("the beer is not back in the stash after the refetch");
					outcome.set(null);
					Wardrobes.update(owner, w -> w.sew(CHAPTER, BEER).orElse(null), outcome::set);
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
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(BEER_PATCH, 1).sew(CHAPTER, BEER).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					OvveItem.syncDesign(player, a);
					OvveItem.syncDesign(player, b);
					if (!BEER.equals(Looks.at(a, Spot.FRONT_TOP_LEFT)) || !BEER.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("the sew did not reach both ovves");
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
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(BEER_PATCH, 1).sew(CHAPTER, BEER).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					OvveItem.refresh(a);
					OvveItem.refresh(b);
					if (!BEER.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("b does not show the beer");
					// Into the hand (a survival server): the store lets go of it first, and only once.
					OwnedSewing.unpick(null, a, Spot.FRONT_TOP_LEFT, false, given::add, refused::add);
				})
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 1, "first unpick not answered"))
				.thenExecute(() -> {
					if (given.size() != 1 || !BEER.equals(given.get(0).placement()) || given.get(0).toStash()) helper.fail("first unpick: given " + given + ", refused " + refused);
					if (Wardrobes.current(owner).count(BEER_PATCH) != 0) helper.fail("an unpick into the hand also left one in the stash");
					// b still carries the old copy; the unpick asks the store, not the item.
					if (!BEER.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("b was refreshed before being asked");
					OwnedSewing.unpick(null, b, Spot.FRONT_TOP_LEFT, false, given::add, refused::add);
				})
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 2, "second unpick not answered"))
				.thenExecute(() -> {
					if (given.size() != 1 || refused.size() != 1) helper.fail("second unpick: given " + given + ", refused " + refused);
					release(server);
				})
				.thenSucceed();
	}

	// ---- ownership: whose ovve this is

	/** Somebody else's ovve does not go on, and one forced into the slot comes off on the next tick. */
	@GameTest
	public void foreignOvveCannotBeWorn(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		ItemStack ovve = new ItemStack(ModContent.ovve(CHAPTER));
		OvveItem.setOwner(ovve, UUID.randomUUID());
		if (player.isEquippableInSlot(ovve, EquipmentSlot.LEGS)) helper.fail("the armour slot took a foreign ovve");
		if (player.canEquipWithDispenser(ovve)) helper.fail("a dispenser could put a foreign ovve on");
		if (Ownership.wearRefusal(player, ovve) == null) helper.fail("no refusal to read for a foreign ovve");
		// Forced in (/item replace, another mod): the wearer's tick takes it off, into their inventory.
		player.setItemSlot(EquipmentSlot.LEGS, ovve);
		ovve.inventoryTick(player.level(), player, EquipmentSlot.LEGS);
		if (!player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) helper.fail("the tick left a foreign ovve on");
		if (!player.getInventory().contains(stack -> stack.getItem() instanceof OvveItem)) helper.fail("the evicted ovve went nowhere");
		helper.succeed();
	}

	@GameTest
	public void ownerCanWearTheirOvve(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		ItemStack ovve = new ItemStack(ModContent.ovve(CHAPTER));
		OvveItem.setOwner(ovve, player.getUUID());
		if (!player.isEquippableInSlot(ovve, EquipmentSlot.LEGS)) helper.fail("the owner could not put their own ovve on");
		if (Ownership.wearRefusal(player, ovve) != null) helper.fail("refused the owner: " + Ownership.wearRefusal(player, ovve));
		player.setItemSlot(EquipmentSlot.LEGS, ovve);
		ovve.inventoryTick(player.level(), player, EquipmentSlot.LEGS);
		if (player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) helper.fail("the tick took the owner's own ovve off");
		helper.succeed();
	}

	@GameTest
	public void unownedOvveBindsToTheFirstWearer(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		ItemStack ovve = new ItemStack(ModContent.ovve(CHAPTER));
		if (!player.isEquippableInSlot(ovve, EquipmentSlot.LEGS)) helper.fail("an unowned ovve would not go on");
		player.setItemSlot(EquipmentSlot.LEGS, ovve);
		ovve.inventoryTick(player.level(), player, EquipmentSlot.LEGS);
		if (!player.getUUID().equals(OvveItem.owner(ovve))) helper.fail("an unowned ovve did not bind to its wearer");
		if (player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) helper.fail("the ovve it just bound to came off again");
		helper.succeed();
	}

	/** rebind: a given ovve becomes the holder's. allow: anyone wears it, still showing its owner's design. */
	@GameTest
	public void othersOvveRebindAndAllowStillWork(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DesignStoreConfig designs = OvvarConfig.get().designs();
		try {
			ItemStack ovve = new ItemStack(ModContent.ovve(CHAPTER));
			UUID someoneElse = UUID.randomUUID();
			OvveItem.setOwner(ovve, someoneElse);
			OvvarConfig.modify(config -> config.designs(designs.othersOvve(DesignStoreConfig.OthersOvve.ALLOW)));
			if (!player.isEquippableInSlot(ovve, EquipmentSlot.LEGS)) helper.fail("allow: a foreign ovve still would not go on");
			if (Ownership.wearRefusal(player, ovve) != null) helper.fail("allow: still refused");
			OvveItem.syncDesign(player, ovve);
			if (!someoneElse.equals(OvveItem.owner(ovve))) helper.fail("allow: the ovve changed hands");

			OvvarConfig.modify(config -> config.designs(designs.othersOvve(DesignStoreConfig.OthersOvve.REBIND)));
			OvveItem.syncDesign(player, ovve);
			if (!player.getUUID().equals(OvveItem.owner(ovve))) helper.fail("rebind: the ovve did not become the holder's");
			if (!player.isEquippableInSlot(ovve, EquipmentSlot.LEGS)) helper.fail("rebind: the rebound ovve would not go on");
		} finally {
			OvvarConfig.modify(config -> config.designs(designs));
		}
		helper.succeed();
	}

	/** rebind is a pickup too: with bind_on_pickup off nothing binds and nothing changes hands. */
	@GameTest
	public void rebindStillNeedsBindOnPickup(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		DesignStoreConfig designs = OvvarConfig.get().designs();
		try {
			OvvarConfig.modify(config -> config.designs(
					designs.othersOvve(DesignStoreConfig.OthersOvve.REBIND).bindOnPickup(false)));
			ItemStack theirs = new ItemStack(ModContent.ovve(CHAPTER));
			UUID someoneElse = UUID.randomUUID();
			OvveItem.setOwner(theirs, someoneElse);
			OvveItem.syncDesign(player, theirs);
			if (!someoneElse.equals(OvveItem.owner(theirs))) helper.fail("rebound an ovve with bind_on_pickup off");
			ItemStack nobodys = new ItemStack(ModContent.ovve(CHAPTER));
			OvveItem.syncDesign(player, nobodys);
			if (OvveItem.owner(nobodys) != null) helper.fail("bound an unowned ovve with bind_on_pickup off");
		} finally {
			OvvarConfig.modify(config -> config.designs(designs));
		}
		helper.succeed();
	}

	/** Shears on somebody else's ovve change nothing: not the store, not the stash, not the ovve. */
	@GameTest(maxTicks = 1200)
	public void foreignOvveCannotBeUnpicked(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
		UUID owner = UUID.randomUUID();
		ItemStack ovve = new ItemStack(ModContent.ovve(CHAPTER));
		OvveItem.setOwner(ovve, owner);
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
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(BEER_PATCH, 1).sew(CHAPTER, BEER).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> {
					OvveItem.refresh(ovve);
					OwnedSewing.unpick(stranger, ovve, Spot.FRONT_TOP_LEFT, false, given::add, refused::add);
				})
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 1, "the unpick was not answered"))
				.thenExecute(() -> {
					if (!given.isEmpty()) helper.fail("a stranger unpicked a patch: " + given);
					if (!refused.get(0).contains("belongs to")) helper.fail("refusal text: " + refused.get(0));
					Wardrobe now = Wardrobes.current(owner);
					if (now.version() != 1) helper.fail("the store moved on a refused unpick: version " + now.version());
					if (now.count(BEER_PATCH) != 0) helper.fail("the stash changed on a refused unpick");
					if (!BEER.equals(Looks.at(ovve, Spot.FRONT_TOP_LEFT))) helper.fail("the ovve lost its patch anyway");
					release(server);
				})
				.thenSucceed();
	}

	/** Nor may a stranger sew on it, even holding the patch. */
	@GameTest(maxTicks = 1200)
	public void foreignOvveCannotBeSewn(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
		UUID owner = UUID.randomUUID();
		ItemStack ovve = new ItemStack(ModContent.ovve(CHAPTER));
		OvveItem.setOwner(ovve, owner);
		List<String> sewn = new ArrayList<>();
		List<String> refused = new ArrayList<>();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				})
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> OwnedSewing.sew(stranger, ovve, HEART, true, () -> sewn.add("sewn"), refused::add))
				.thenWaitUntil(() -> assertThat(sewn.size() + refused.size() == 1, "the sew was not answered"))
				.thenExecute(() -> {
					if (!sewn.isEmpty()) helper.fail("a stranger sewed on somebody else's ovve");
					if (!refused.get(0).contains("belongs to")) helper.fail("refusal text: " + refused.get(0));
					if (Wardrobes.current(owner).version() != 0) helper.fail("the store moved on a refused sew");
					release(server);
				})
				.thenSucceed();
	}

	/** The owner themselves sews and unpicks as before. */
	@GameTest(maxTicks = 1200)
	public void ownerCanUnpickAndSew(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		UUID owner = player.getUUID();
		ItemStack ovve = new ItemStack(ModContent.ovve(CHAPTER));
		OvveItem.setOwner(ovve, owner);
		List<String> sewn = new ArrayList<>();
		List<OwnedSewing.Unpicked> given = new ArrayList<>();
		List<String> refused = new ArrayList<>();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				})
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> OwnedSewing.sew(player, ovve, BEER, true, () -> sewn.add("sewn"), refused::add))
				.thenWaitUntil(() -> assertThat(sewn.size() + refused.size() == 1, "the sew was not answered"))
				.thenExecute(() -> {
					if (sewn.isEmpty()) helper.fail("the owner could not sew on their own ovve: " + refused);
					if (!BEER.equals(Looks.at(ovve, Spot.FRONT_TOP_LEFT))) helper.fail("the sew did not reach the ovve");
					OwnedSewing.unpick(player, ovve, Spot.FRONT_TOP_LEFT, true, given::add, refused::add);
				})
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 1, "the unpick was not answered"))
				.thenExecute(() -> {
					if (given.isEmpty()) helper.fail("the owner could not unpick from their own ovve: " + refused);
					if (Wardrobes.current(owner).count(BEER_PATCH) != 1) helper.fail("the unpicked patch is not in the stash");
					release(server);
				})
				.thenSucceed();
	}

	// ---- the config file's _help

	/**
	 * {@code config/ovvar.json} and its {@code designs}, {@code stash} and {@code server} blocks
	 * each carry a {@code _help} object with an entry for every key they write, plus {@code _about};
	 * this is the only comment JSON gets, so a key silently missing its line is worth failing on.
	 */
	@GameTest
	public void configHelpCoversEveryKey(GameTestHelper helper) {
		// The plain factory-default config, not nudged off default anywhere: server/designs/stash
		// (and jdbc inside designs) use optionalFieldOf(key).xmap(...) precisely so a block equal to
		// its own default is still written (and so is its _help), unlike the scalar keys inside each
		// block, which optionalFieldOf(key, default) still omits when they equal that default.
		OvvarConfig config = new OvvarConfig(true, 6, ServerConfig.DEFAULT, DesignStoreConfig.DEFAULT, StashConfig.DEFAULT);
		JsonElement json = OvvarConfig.CODEC.codec().encodeStart(JsonOps.INSTANCE, config)
				.getOrThrow(message -> new IllegalStateException("config does not encode: " + message));
		JsonObject root = json.getAsJsonObject();
		assertHelpCoversKeys(helper, root, OvvarConfig.HELP, "root");
		assertHelpCoversKeys(helper, root.getAsJsonObject("server"), ServerConfig.HELP, "server");
		JsonObject designs = root.getAsJsonObject("designs");
		assertHelpCoversKeys(helper, designs, DesignStoreConfig.HELP, "designs");
		assertHelpCoversKeys(helper, designs == null ? null : designs.getAsJsonObject("jdbc"), DesignStoreConfig.Jdbc.HELP, "designs.jdbc");
		assertHelpCoversKeys(helper, root.getAsJsonObject("stash"), StashConfig.HELP, "stash");
		helper.succeed();
	}

	private static void assertHelpCoversKeys(GameTestHelper helper, JsonObject block, Map<String, String> help, String name) {
		if (block == null) { helper.fail(name + " block was not written at all"); return; }
		if (!block.has("_help")) helper.fail(name + " has no _help");
		JsonObject written = block.getAsJsonObject("_help");
		if (!written.has("_about")) helper.fail(name + "._help has no _about");
		for (String key : block.keySet()) {
			if (key.equals("_help")) continue;
			if (!written.has(key)) helper.fail(name + "._help is missing an entry for " + key);
		}
		for (String key : help.keySet()) {
			if (!written.has(key)) helper.fail(name + "._help does not match its HELP map: missing " + key);
		}
	}

	// ---- the wardrobe screen

	/** The title carries the ovvar:wardrobe font and the chapter's own glyph codepoint. */
	@GameTest
	public void wardrobeTitleCarriesTheChapterGlyph(GameTestHelper helper) {
		for (Chapter tab : Chapter.values()) {
			Component title = WardrobeGui.title(tab, Wardrobe.NONE, Piece.TOP);
			char glyph = WardrobeArt.chapterChar(tab);
			boolean foundGlyph = false, foundFont = false;
			for (Component part : allParts(title)) {
				if (WardrobeArt.FONT.equals(part.getStyle().getFont() instanceof net.minecraft.network.chat.FontDescription.Resource r ? r.id() : null)) {
					foundFont = true;
					if (part.getString().indexOf(glyph) >= 0) foundGlyph = true;
				}
			}
			if (!foundFont) helper.fail(tab + ": no part of the title uses the wardrobe font");
			if (!foundGlyph) helper.fail(tab + ": no part of the title carries its glyph U+" + Integer.toHexString(glyph));
		}
		helper.succeed();
	}

	private static List<Component> allParts(Component component) {
		List<Component> out = new ArrayList<>();
		out.add(component);
		for (Component sibling : component.getSiblings()) out.addAll(allParts(sibling));
		return out;
	}

	/** A wardrobe with 3 stash kinds and 2 sewn placements fills exactly that many collection and preview slots. */
	@GameTest(maxTicks = 1200)
	public void wardrobeSlotsFollowTheWardrobe(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		UUID owner = player.getUUID();
		AtomicReference<Wardrobes.Outcome> outcome = new AtomicReference<>();
		Patches.Patch gasque = Patches.get("gasque"), kth = Patches.get("kth"), nolle = Patches.get("nolle");
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				})
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(gasque, 1).add(kth, 1).add(nolle, 1)
						.add(BEER_PATCH, 1).sew(CHAPTER, BEER).orElseThrow()
						.add(HEART_PATCH, 1).sew(CHAPTER, HEART).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "setup outcome " + outcome.get()))
				.thenExecute(() -> {
					WardrobeGui gui = WardrobeGui.forTest(player, CHAPTER, Piece.TOP);
					int filledCollection = 0;
					for (int i = 0; i < 20; i++) {
						int slot = 9 + (i / 5) * 9 + (i % 5);
						if (!isEmpty(gui, slot)) filledCollection++;
					}
					if (filledCollection != 3) helper.fail("collection slots filled: " + filledCollection + ", wanted 3");
					if (isEmpty(gui, WardrobeGui.previewSlot(BEER.spot()))) helper.fail("no preview item at the beer's slot");
					if (isEmpty(gui, WardrobeGui.previewSlot(HEART.spot()))) helper.fail("no preview item at the heart's slot");
					if (gui.getGuiElement(WardrobeGui.previewSlot(BEER.spot())).getGuiCallback() != GuiElement.EMPTY_CALLBACK) {
						helper.fail("a preview item has a click callback; it should be hover-only");
					}
					// survival mode: every action is present
					for (int slot : new int[]{45, 46, 47, 48, 52, 53}) {
						if (isEmpty(gui, slot)) helper.fail("action slot " + slot + " missing on a survival server");
					}
					if (!isEmpty(gui, 49)) helper.fail("finish-sewing button present with no session running");
				})
				.thenExecute(() -> {
					// With a session running, "finish sewing" (col 4 of the action row, slot 49) appears.
					StashConfig sessions = OvvarConfig.get().stash();
					try {
						StashConfig withSessions = new StashConfig(sessions.minigameServer(), sessions.sewGameModes(), sessions.ingameObjective(),
								sessions.bankOnPickup(), sessions.bankInCreative(), sessions.unpickToStash(), sessions.withdraw(), true,
								sessions.stashClick(), sessions.anyStand(), sessions.sessionReach(), sessions.sessionSeconds(), sessions.explainInChat());
						OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(), withSessions));
						StashSession.start(player, gasque, why -> helper.fail("could not start a session: " + why));
						WardrobeGui withSession = WardrobeGui.forTest(player, CHAPTER, Piece.TOP);
						if (isEmpty(withSession, 49)) helper.fail("finish-sewing button missing with a session running");
						StashSession.end(player, null);
					} finally {
						OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(), sessions));
					}
					release(server);
				})
				.thenSucceed();
	}

	/** On a minigame server the take-out/sew reminders are gone; the help book still explains why. */
	@GameTest(maxTicks = 1200)
	public void wardrobeActionsRespectTheMode(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		UUID owner = player.getUUID();
		StashConfig stash = OvvarConfig.get().stash();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				})
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> {
					try {
						OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(),
								new StashConfig(true, stash.sewGameModes(), stash.ingameObjective(), stash.bankOnPickup(), stash.bankInCreative(),
										stash.unpickToStash(), stash.withdraw(), stash.sessions(), stash.stashClick(), stash.anyStand(),
										stash.sessionReach(), stash.sessionSeconds(), stash.explainInChat())));
						WardrobeGui gui = WardrobeGui.forTest(player, CHAPTER, Piece.TOP);
						if (!isEmpty(gui, 45)) helper.fail("take-out reminder present on a minigame server");
						if (!isEmpty(gui, 47)) helper.fail("sew reminder present on a minigame server");
						if (isEmpty(gui, 52)) helper.fail("no help book on a minigame server");
						GuiElement help = gui.getGuiElement(52);
						var itemLore = help.getItemStack().get(net.minecraft.core.component.DataComponents.LORE);
						boolean sawLookOnly = itemLore != null && itemLore.lines().stream()
								.anyMatch(line -> line.getString().toLowerCase(java.util.Locale.ROOT).contains("look"));
						if (!sawLookOnly) helper.fail("help book does not explain the server is look-only");
					} finally {
						OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(), stash));
					}
					release(server);
				})
				.thenSucceed();
	}

	/** The tab row only ever lists chapters the player owns an ovve of. */
	@GameTest
	public void foreignOvveHasNoWardrobeTab(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		Chapter owned = CHAPTER, foreign = Chapter.values()[1];
		ItemStack mine = new ItemStack(ModContent.ovve(owned));
		OvveItem.setOwner(mine, player.getUUID());
		player.getInventory().add(mine);
		ItemStack theirs = new ItemStack(ModContent.ovve(foreign));
		OvveItem.setOwner(theirs, UUID.randomUUID());
		player.getInventory().add(theirs);
		List<Chapter> tabs = WardrobeGui.ownedChapters(player);
		if (!tabs.contains(owned)) helper.fail("the player's own chapter is missing from the tabs: " + tabs);
		if (tabs.contains(foreign)) helper.fail("a foreign ovve's chapter is shown as a tab: " + tabs);
		helper.succeed();
	}

	/**
	 * Every chapter gets a background PNG and a font entry; the real checked-in template (and so
	 * every tint) is {@code WardrobeArt.WIDTH x WardrobeArt.HEIGHT} (176x126: exactly the
	 * GENERIC_9x6 container's own rows, nothing more); the font's space provider is exactly the
	 * better-pets-style {@code {a: -8, c: -169}} advances.
	 */
	@GameTest
	public void wardrobeArtGeneratesFontAndBackgroundPerChapter(GameTestHelper helper) {
		JsonObject font = WardrobeArt.fontJson();
		JsonObject space = font.getAsJsonArray("providers").get(0).getAsJsonObject();
		if (!space.get("type").getAsString().equals("space")) helper.fail("the first provider is not the space provider: " + space);
		JsonObject advances = space.getAsJsonObject("advances");
		if (advances.get("a").getAsInt() != -8 || advances.get("c").getAsInt() != -169) {
			helper.fail("space advances are not {a: -8, c: -169}: " + advances);
		}
		int bitmaps = 0;
		for (JsonElement provider : font.getAsJsonArray("providers")) {
			JsonObject o = provider.getAsJsonObject();
			if (!o.get("type").getAsString().equals("bitmap")) continue;
			bitmaps++;
			if (o.get("height").getAsInt() != WardrobeArt.HEIGHT) helper.fail("bitmap provider height is not " + WardrobeArt.HEIGHT + ": " + o);
		}
		if (bitmaps != Chapter.values().length) helper.fail("expected one background per chapter (" + Chapter.values().length + "), font has " + bitmaps);

		var template = WardrobeArt.readTemplate();
		if (template.getWidth() != WardrobeArt.WIDTH || template.getHeight() != WardrobeArt.HEIGHT) {
			helper.fail("the real template is " + template.getWidth() + "x" + template.getHeight() + ", wanted " + WardrobeArt.WIDTH + "x" + WardrobeArt.HEIGHT);
		}
		for (Chapter tab : Chapter.values()) {
			var tinted = WardrobeArt.tint(template, WardrobeArt.colour(tab));
			if (tinted.getWidth() != WardrobeArt.WIDTH || tinted.getHeight() != WardrobeArt.HEIGHT) {
				helper.fail(tab + " background is " + tinted.getWidth() + "x" + tinted.getHeight() + ", wanted " + WardrobeArt.WIDTH + "x" + WardrobeArt.HEIGHT);
			}
		}
		helper.succeed();
	}

	private static boolean isEmpty(WardrobeGui gui, int slot) {
		GuiElement element = gui.getGuiElement(slot);
		return element == null || element.getItemStack().isEmpty();
	}

	/** {@code /ovvar stash} runs the command handler that opens the wardrobe screen (not the old StashGui). */
	@GameTest
	public void stashCommandOpensWardrobeGui(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		MinecraftServer server = helper.getLevel().getServer();
		var before = player.containerMenu;
		server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "ovvar stash");
		if (player.containerMenu == before) helper.fail("/ovvar stash did not open a menu");
		helper.succeed();
	}

	// ---- the wardrobe mannequin

	/** One per player (a second click despawns the first); refused on a minigame server; the copy loses its bundle contents. */
	@GameTest
	public void wardrobeMannequinIsOneAtATimeAndDupeSafe(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		StashConfig stash = OvvarConfig.get().stash();
		try {
			ItemStack ovve = new ItemStack(ModContent.ovve(CHAPTER));
			OvveItem.setOwner(ovve, player.getUUID());
			var bundle = new net.minecraft.world.item.component.BundleContents.Mutable();
			bundle.tryInsert(new ItemStack(net.minecraft.world.item.Items.DIAMOND, 64));
			ovve.set(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS, bundle.toImmutable());

			OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(),
					new StashConfig(true, stash.sewGameModes(), stash.ingameObjective(), stash.bankOnPickup(), stash.bankInCreative(),
							stash.unpickToStash(), stash.withdraw(), stash.sessions(), stash.stashClick(), stash.anyStand(),
							stash.sessionReach(), stash.sessionSeconds(), stash.explainInChat())));
			String refusal = WardrobeMannequin.show(player, ovve);
			if (refusal == null) helper.fail("no refusal on a minigame server");
			if (WardrobeMannequin.mannequinOf(player) != null) helper.fail("a mannequin was tracked despite the refusal");

			OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(), stash));
			String ok = WardrobeMannequin.show(player, ovve);
			if (ok != null) helper.fail("refused on a survival server: " + ok);
			var m = WardrobeMannequin.mannequinEntityOf(player);
			if (m == null) helper.fail("no mannequin tracked after show()");
			ItemStack worn = m.getItemBySlot(EquipmentSlot.LEGS);
			if (worn.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS)) helper.fail("the mannequin's ovve still carries BUNDLE_CONTENTS");
			if (!m.isPermanentlyInvulnerable()) helper.fail("the mannequin is not invulnerable");
			if (!m.entityTags().contains(WardrobeMannequin.TAG)) helper.fail("the mannequin is not tagged " + WardrobeMannequin.TAG);

			UUID beforeSecond = WardrobeMannequin.mannequinOf(player);
			WardrobeMannequin.show(player, ovve);
			var m2 = WardrobeMannequin.mannequinEntityOf(player);
			if (m2 == null) helper.fail("no mannequin tracked after the second show()");
			if (WardrobeMannequin.mannequinOf(player).equals(beforeSecond)) helper.fail("the second click reused the same entity instead of replacing it");
			if (!m.isRemoved()) helper.fail("the first mannequin was not discarded by the second click");
		} finally {
			OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(), stash));
		}
		helper.succeed();
	}

	// ---- the MOTD

	@GameTest
	public void motdNamesTheServerMode(GameTestHelper helper) {
		String survival = Motd.text("Testcraft", false);
		String minigame = Motd.text("Testcraft", true);
		if (!survival.startsWith("Testcraft ") || !minigame.startsWith("Testcraft ")) helper.fail("the MOTD does not name the server: " + survival + " / " + minigame);
		if (!survival.contains("Survival") || !survival.contains("sewing")) helper.fail("survival MOTD: " + survival);
		if (!minigame.contains("Minigame") || minigame.contains("sewing on stands")) helper.fail("minigame MOTD: " + minigame);
		if (!Motd.text("", false).startsWith(ServerConfig.DEFAULT.name())) helper.fail("a nameless server does not fall back on a name");
		OvvarConfig config = OvvarConfig.get();
		if (!Motd.text(config).equals(Motd.text(config.server().name(), config.stash().minigameServer()))) {
			helper.fail("this server's MOTD is not its config's: " + Motd.text(config));
		}
		helper.succeed();
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
