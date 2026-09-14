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
import metacraft.ovvar.pack.Combos;
import metacraft.ovvar.pack.WardrobeArt;
import metacraft.ovvar.pack.WardrobeFont;
import metacraft.ovvar.pack.WardrobePreview;
import metacraft.ovvar.pack.WardrobePreview.Angle;
import metacraft.ovvar.sewing.WardrobeAction;
import metacraft.ovvar.sewing.WardrobeGui;
import metacraft.ovvar.sewing.WardrobeMannequin;
import metacraft.ovvar.sewing.StashSession;
import eu.pb4.sgui.api.elements.GuiElement;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * one take turns ({@link #BUSY}: game tests in a batch run together) and put a throwaway one back —
 * never the run dir's configured store (a live JDBC backend, in a deployed run dir, whose rows are
 * real): every mock player these tests spawn joins for real and is fetched on join, so the
 * configured store must never be the thing listening when that happens.
 */
public final class WardrobeTests {
	/** Held by whichever sequence test is using the server's wardrobe store right now. */
	private static final AtomicBoolean BUSY = new AtomicBoolean();

	static {
		// The moment the server is up — before the first test spawns a mock player and well before
		// any test claims BUSY — detach the store from whatever config/ovvar.json names. Fabric-api's
		// GameTest has no batch() to force these onto one worker (javap confirms), so without this a
		// stray on-join fetch for a fixed test UUID can hit a live JDBC store with real rows before
		// any test-owned backend is in place.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> Wardrobes.use(server, idleBackend()));
	}

	private static WardrobeBackend idleBackend() {
		try {
			return new FileBackend(Files.createTempDirectory("ovvar-wardrobes-idle"));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Runs a store test's step; whatever it throws (a failed assertion or anything else) the lock
	 * and the throwaway backend come back first, so a broken test never leaves {@link #BUSY} held
	 * forever and turns every other store test's wait into a permanent "another store test is
	 * running" failure instead of the retry it is meant to be.
	 */
	private static void guarded(MinecraftServer server, Runnable step) {
		try {
			step.run();
		} catch (RuntimeException | Error e) {
			release(server);
			throw e;
		}
	}

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
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> guarded(server, () -> {
					if (Wardrobes.current(owner).version() != 1) helper.fail("cached version " + Wardrobes.current(owner).version() + ", wanted 1");
					// Another server unpicks and sews on: the store is at version 5 with only the Nyckeln on.
					try {
						Wardrobe theirs = Wardrobe.NONE.add(ITK_PATCH, 1).add(NYCKELN_PATCH, 1).sew(CHAPTER, NYCKELN).orElseThrow().withVersion(5);
						if (!behind.store(owner, theirs, 1)) helper.fail("behind-the-back write refused");
					} catch (IOException e) {
						throw new GameTestAssertException(Component.literal(e.toString()), 0);
					}
					outcome.set(null);
					Wardrobes.update(owner, w -> w.unpick(CHAPTER, Spot.FRONT_TOP_LEFT).orElse(null), outcome::set);
				}))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.CONFLICT, "stale write outcome " + outcome.get()))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner) && Wardrobes.current(owner).version() == 5, "cache not refetched to version 5"))
				.thenExecute(() -> guarded(server, () -> {
					if (Wardrobes.current(owner).at(CHAPTER, Spot.FRONT_TOP_LEFT).isPresent()) helper.fail("the itk survived the refetch");
					if (Wardrobes.current(owner).count(ITK_PATCH) != 1) helper.fail("the itk is not back in the stash after the refetch");
					outcome.set(null);
					Wardrobes.update(owner, w -> w.sew(CHAPTER, ITK).orElse(null), outcome::set);
				}))
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
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					// Their first tick in a player's inventory binds them; nothing to adopt, both are plain.
					OvveItem.syncDesign(player, a);
					OvveItem.syncDesign(player, b);
					if (!owner.equals(OvveItem.owner(a)) || !owner.equals(OvveItem.owner(b))) helper.fail("not bound on pickup");
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> guarded(server, () -> {
					OvveItem.syncDesign(player, a);
					OvveItem.syncDesign(player, b);
					if (!ITK.equals(Looks.at(a, Spot.FRONT_TOP_LEFT)) || !ITK.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("the sew did not reach both ovves");
					outcome.set(null);
					Wardrobes.update(owner, w -> w.unpick(CHAPTER, Spot.FRONT_TOP_LEFT).orElse(null), outcome::set);
				}))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "unpick outcome " + outcome.get()))
				.thenExecute(() -> guarded(server, () -> {
					OvveItem.syncDesign(player, a);
					OvveItem.syncDesign(player, b);
					if (Looks.at(a, Spot.FRONT_TOP_LEFT) != null || Looks.at(b, Spot.FRONT_TOP_LEFT) != null) helper.fail("the unpick did not reach both ovves");
					release(server);
				}))
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
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> guarded(server, () -> {
					OvveItem.refresh(a);
					OvveItem.refresh(b);
					if (!ITK.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("b does not show the ITK");
					// Into the hand (a survival server): the store lets go of it first, and only once.
					OwnedSewing.unpick(null, a, Spot.FRONT_TOP_LEFT, false, given::add, refused::add);
				}))
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 1, "first unpick not answered"))
				.thenExecute(() -> guarded(server, () -> {
					if (given.size() != 1 || !ITK.equals(given.get(0).placement()) || given.get(0).toStash()) helper.fail("first unpick: given " + given + ", refused " + refused);
					if (Wardrobes.current(owner).count(ITK_PATCH) != 0) helper.fail("an unpick into the hand also left one in the stash");
					// b still carries the old copy; the unpick asks the store, not the item.
					if (!ITK.equals(Looks.at(b, Spot.FRONT_TOP_LEFT))) helper.fail("b was refreshed before being asked");
					OwnedSewing.unpick(null, b, Spot.FRONT_TOP_LEFT, false, given::add, refused::add);
				}))
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 2, "second unpick not answered"))
				.thenExecute(() -> guarded(server, () -> {
					if (given.size() != 1 || refused.size() != 1) helper.fail("second unpick: given " + given + ", refused " + refused);
					release(server);
				}))
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
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "sew outcome " + outcome.get()))
				.thenExecute(() -> guarded(server, () -> {
					OvveItem.refresh(ovve);
					OwnedSewing.unpick(stranger, ovve, Spot.FRONT_TOP_LEFT, false, given::add, refused::add);
				}))
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 1, "the unpick was not answered"))
				.thenExecute(() -> guarded(server, () -> {
					if (!given.isEmpty()) helper.fail("a stranger unpicked a patch: " + given);
					if (!refused.get(0).contains("belongs to")) helper.fail("refusal text: " + refused.get(0));
					Wardrobe now = Wardrobes.current(owner);
					if (now.version() != 1) helper.fail("the store moved on a refused unpick: version " + now.version());
					if (now.count(ITK_PATCH) != 0) helper.fail("the stash changed on a refused unpick");
					if (!ITK.equals(Looks.at(ovve, Spot.FRONT_TOP_LEFT))) helper.fail("the ovve lost its patch anyway");
					release(server);
				}))
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
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> OwnedSewing.sew(stranger, ovve, NYCKELN, true, () -> sewn.add("sewn"), refused::add))
				.thenWaitUntil(() -> assertThat(sewn.size() + refused.size() == 1, "the sew was not answered"))
				.thenExecute(() -> guarded(server, () -> {
					if (!sewn.isEmpty()) helper.fail("a stranger sewed on somebody else's ovve");
					if (!refused.get(0).contains("belongs to")) helper.fail("refusal text: " + refused.get(0));
					if (Wardrobes.current(owner).version() != 0) helper.fail("the store moved on a refused sew");
					release(server);
				}))
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
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> OwnedSewing.sew(player, ovve, ITK, true, () -> sewn.add("sewn"), refused::add))
				.thenWaitUntil(() -> assertThat(sewn.size() + refused.size() == 1, "the sew was not answered"))
				.thenExecute(() -> guarded(server, () -> {
					if (sewn.isEmpty()) helper.fail("the owner could not sew on their own ovve: " + refused);
					if (!ITK.equals(Looks.at(ovve, Spot.FRONT_TOP_LEFT))) helper.fail("the sew did not reach the ovve");
					// The store's own copy, not the ovve's, is what unpick reads: re-seed it straight from the
					// (isolated) backend rather than trust the cache to still show what the sew callback just
					// wrote — a mock player join anywhere else in the suite can fetch this same fixed test
					// UUID in between and clobber the shared cache with whatever it finds.
					Wardrobes.refresh(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner) && Wardrobes.current(owner).at(CHAPTER, Spot.FRONT_TOP_LEFT).isPresent(),
						"the re-seeded wardrobe does not show the ITK"))
				.thenExecute(() -> guarded(server, () ->
						OwnedSewing.unpick(player, ovve, Spot.FRONT_TOP_LEFT, true, given::add, refused::add)))
				.thenWaitUntil(() -> assertThat(given.size() + refused.size() == 1, "the unpick was not answered"))
				.thenExecute(() -> guarded(server, () -> {
					if (given.isEmpty()) helper.fail("the owner could not unpick from their own ovve: " + refused);
					if (Wardrobes.current(owner).count(ITK_PATCH) != 1) helper.fail("the unpicked patch is not in the stash");
					release(server);
				}))
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
			Component title = WardrobeGui.title(tab, Wardrobe.NONE, Angle.FRONT);
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

		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				// Two of each: one of each sewn on, one of each left in the stash, so both the
				// collection and the preview have something to fill (the catalogue has two patches).
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(ITK_PATCH, 2).add(NYCKELN_PATCH, 2)
						.sew(CHAPTER, ITK).orElseThrow()
						.sew(CHAPTER, NYCKELN).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "setup outcome " + outcome.get()))
				.thenExecute(() -> guarded(server, () -> {
					WardrobeGui gui = WardrobeGui.forTest(player, CHAPTER, Angle.FRONT);
					int filledCollection = 0;
					for (int i = 0; i < 20; i++) {
						int slot = 9 + (i / 5) * 9 + (i % 5);
						if (!isEmpty(gui, slot)) filledCollection++;
					}
					if (filledCollection != 2) helper.fail("collection slots filled: " + filledCollection + ", wanted 2 (one kind per patch left in the stash)");
					if (isEmpty(gui, WardrobeGui.previewSlot(ITK.spot()))) helper.fail("no preview item at the ITK's slot");
					if (isEmpty(gui, WardrobeGui.previewSlot(NYCKELN.spot()))) helper.fail("no preview item at the Nyckeln's slot");
					if (gui.getGuiElement(WardrobeGui.previewSlot(ITK.spot())).getGuiCallback() != GuiElement.EMPTY_CALLBACK) {
						helper.fail("a preview item has a click callback; it should be hover-only");
					}
					// survival mode: every action is present
					for (int slot : new int[]{45, 46, 47, 48, 52, 53}) {
						if (isEmpty(gui, slot)) helper.fail("action slot " + slot + " missing on a survival server");
					}
					if (!isEmpty(gui, 49)) helper.fail("finish-sewing button present with no session running");
				}))
				.thenExecute(() -> guarded(server, () -> {
					// With a session running, "finish sewing" (col 4 of the action row, slot 49) appears.
					StashConfig sessions = OvvarConfig.get().stash();
					try {
						StashConfig withSessions = new StashConfig(sessions.minigameServer(), sessions.sewGameModes(), sessions.ingameObjective(),
								sessions.bankOnPickup(), sessions.bankInCreative(), sessions.unpickToStash(), sessions.withdraw(), true,
								sessions.stashClick(), sessions.anyStand(), sessions.sessionReach(), sessions.sessionSeconds(), sessions.explainInChat());
						OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(), withSessions));
						StashSession.start(player, NYCKELN_PATCH, why -> helper.fail("could not start a session: " + why));
						WardrobeGui withSession = WardrobeGui.forTest(player, CHAPTER, Angle.FRONT);
						if (isEmpty(withSession, 49)) helper.fail("finish-sewing button missing with a session running");
						StashSession.end(player, null);
					} finally {
						OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(), sessions));
					}
					release(server);
				}))
				.thenSucceed();
	}

	/** On a minigame server the take-out and sew actions are grey panes carrying the reason; the help book still explains why. */
	@GameTest(maxTicks = 1200)
	public void wardrobeActionsRespectTheMode(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		UUID owner = player.getUUID();
		StashConfig stash = OvvarConfig.get().stash();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> guarded(server, () -> {
					try {
						OvvarConfig.modify(config -> new OvvarConfig(config.sewingMinigame(), config.stitches(), config.server(), config.designs(),
								new StashConfig(true, stash.sewGameModes(), stash.ingameObjective(), stash.bankOnPickup(), stash.bankInCreative(),
										stash.unpickToStash(), stash.withdraw(), stash.sessions(), stash.stashClick(), stash.anyStand(),
										stash.sessionReach(), stash.sessionSeconds(), stash.explainInChat())));
						WardrobeGui gui = WardrobeGui.forTest(player, CHAPTER, Angle.FRONT);
						// Not gone, and not a pane of grey glass either: the action's own icon, dimmed
						// and slashed, named "(not here)" and carrying the reason.
						Map<Integer, WardrobeAction> refused = Map.of(45, WardrobeAction.TAKE_OUT, 47, WardrobeAction.SEW, 48, WardrobeAction.SEE_3D);
						for (var entry : refused.entrySet()) {
							int slot = entry.getKey();
							WardrobeAction what = entry.getValue();
							if (isEmpty(gui, slot)) helper.fail(what + " is missing instead of dimmed on a minigame server");
							ItemStack item = gui.getGuiElement(slot).getItemStack();
							Identifier model = item.get(DataComponents.ITEM_MODEL);
							if (!what.model(false).equals(model)) helper.fail(what + " wears the model " + model + ", wanted " + what.model(false));
							if (!item.getHoverName().getString().contains("(not here)")) {
								helper.fail(what + " is not named \"… (not here)\": " + item.getHoverName().getString());
							}
							if (!lore(item).contains(StashConfig.LOOK_ONLY)) helper.fail(what + " does not carry the reason: " + lore(item));
							if (gui.getGuiElement(slot).getGuiCallback() != GuiElement.EMPTY_CALLBACK) {
								helper.fail(what + " is refused but still clickable");
							}
						}
						// And an action that is allowed wears the plain icon, with no reason on it.
						ItemStack allowed = gui.getGuiElement(46).getItemStack();
						if (!WardrobeAction.PUT_IN.model(true).equals(allowed.get(DataComponents.ITEM_MODEL))) {
							helper.fail("an allowed action does not wear its plain icon: " + allowed.get(DataComponents.ITEM_MODEL));
						}
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
				}))
				.thenSucceed();
	}

	/**
	 * The screen says what it is: a tab per owned ovve, the one on show glinting and saying
	 * "(showing)" and the others saying a click switches to them; exactly one piece toggle, at the
	 * far end of the tab row, named for the half on show and the half a click brings up; and an
	 * empty stash and an unsewn half each saying what would be there.
	 */
	@GameTest(maxTicks = 1200)
	public void wardrobeTabsAndEmptyStatesExplainThemselves(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		UUID owner = player.getUUID();
		Chapter shown = CHAPTER, other = Chapter.values()[1];
		for (Chapter chapter : List.of(shown, other)) {
			ItemStack ovve = new ItemStack(ModContent.ovve(chapter));
			OvveItem.setOwner(ovve, owner);
			player.getInventory().add(ovve);
		}
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> guarded(server, () -> {
					WardrobeGui gui = WardrobeGui.forTest(player, shown, Angle.FRONT);

					ItemStack active = gui.getGuiElement(0).getItemStack();
					if (active.getItem() != ModContent.ovve(shown)) helper.fail("the first tab is " + active.getItem() + ", wanted the shown chapter's ovve");
					if (!Boolean.TRUE.equals(active.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE))) helper.fail("the tab on show does not glint");
					if (!lore(active).contains("(showing)")) helper.fail("the tab on show does not say \"(showing)\": " + lore(active));
					ItemStack sleeping = gui.getGuiElement(1).getItemStack();
					if (Boolean.TRUE.equals(sleeping.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE))) helper.fail("a tab that is not on show glints");
					if (!lore(sleeping).toLowerCase(java.util.Locale.ROOT).contains("click to switch")) helper.fail("a tab does not say a click switches to it: " + lore(sleeping));

					// Nothing but tabs between them and the two rotation buttons at the far end.
					for (int col = 2; col < 9; col++) {
						boolean rotator = col == WardrobeGui.ROTATE_LEFT_COL || col == WardrobeGui.ROTATE_RIGHT_COL;
						if (!rotator && !isEmpty(gui, col)) helper.fail("something else is in the tab row at col " + col);
					}

					// An empty stash and an unsewn half each say so across the whole panel, in the glyph
					// layer: no item anywhere in either panel, and the notice in the title instead.
					for (int slot = 9; slot < 45; slot++) {
						if (!isEmpty(gui, slot)) helper.fail("slot " + slot + " has an item in it with an empty stash and nothing sewn");
					}
					String title = gui.getTitle().getString();
					if (title.indexOf(WardrobeFont.NO_PATCHES.codepoint()) < 0) helper.fail("no \"no patches yet\" notice in the title of an empty stash");
					if (title.indexOf(WardrobeFont.NOTHING_SEWN.codepoint()) < 0) helper.fail("no \"nothing sewn yet\" notice in the title of an unsewn half");
					if (!title.contains(WardrobeFont.at(WardrobeFont.NO_PATCHES))) helper.fail("the empty-stash notice is not placed over the collection panel");
					if (!title.contains(WardrobeFont.at(WardrobeFont.NOTHING_SEWN))) helper.fail("the unsewn notice is not placed over the preview panel");
					release(server);
				}))
				.thenSucceed();
	}

	/**
	 * The empty-state notices are art over their panel, and only when the panel is empty: a stash
	 * with a patch in it, or a half with a patch sewn on it, loses its notice.
	 */
	@GameTest
	public void wardrobeNoticesOnlyShowWhileThePanelIsEmpty(GameTestHelper helper) {
		char noPatches = WardrobeFont.NO_PATCHES.codepoint(), nothingSewn = WardrobeFont.NOTHING_SEWN.codepoint();
		String bare = WardrobeGui.title(CHAPTER, Wardrobe.NONE, Angle.FRONT).getString();
		if (bare.indexOf(noPatches) < 0 || bare.indexOf(nothingSewn) < 0) helper.fail("an empty wardrobe is missing a notice");

		String stashed = WardrobeGui.title(CHAPTER, Wardrobe.NONE.add(ITK_PATCH, 1), Angle.FRONT).getString();
		if (stashed.indexOf(noPatches) >= 0) helper.fail("the \"no patches yet\" notice is still drawn over a stash with a patch in it");
		if (stashed.indexOf(nothingSewn) < 0) helper.fail("the \"nothing sewn yet\" notice went missing while nothing is sewn");

		Wardrobe sewn = Wardrobe.NONE.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElseThrow();
		String worn = WardrobeGui.title(CHAPTER, sewn, Angle.FRONT).getString();
		if (worn.indexOf(nothingSewn) >= 0) helper.fail("the \"nothing sewn yet\" notice is still drawn over a sewn-on half");
		if (worn.indexOf(noPatches) < 0) helper.fail("the \"no patches yet\" notice went missing while the stash is empty");

		// Both notices are pixel text this font can actually draw, and neither is wider than its panel.
		for (WardrobeFont.Glyph notice : List.of(WardrobeFont.NO_PATCHES, WardrobeFont.NOTHING_SEWN)) {
			var art = notice.art().get();
			if (art.width != notice.width() || art.height != notice.height()) helper.fail(notice.name() + " art is " + art.width + "x" + art.height);
			if (notice.x() + notice.width() > WardrobeArt.WIDTH) helper.fail(notice.name() + " runs past the right edge of the screen");
		}
		helper.succeed();
	}

	/** Every lore line of a stack, joined — what a player reads when they hover it. */
	private static String lore(ItemStack stack) {
		var lore = stack.get(DataComponents.LORE);
		if (lore == null) return "";
		return String.join(" | ", lore.lines().stream().map(Component::getString).toList());
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
		// And the bit set of +/-1 ... +/-128 that puts everything else where it goes, each advance
		// cancelling out against its opposite so a glyph run leaves the cursor where it found it.
		WardrobeFont.spaceAdvances().forEach((c, advance) -> {
			if (!advances.has(String.valueOf(c))) helper.fail("the font is missing the space for " + advance);
			else if (advances.get(String.valueOf(c)).getAsInt() != advance) helper.fail("the space for " + advance + " advances " + advances.get(String.valueOf(c)));
		});
		for (int move : new int[]{1, 7, 93, -158, 255}) {
			int sum = 0;
			for (char c : WardrobeFont.move(move).toCharArray()) sum += advances.get(String.valueOf(c)).getAsInt();
			if (sum != move) helper.fail("a move of " + move + " px advances " + sum);
		}
		List<String> backgroundFiles = new ArrayList<>();
		for (Chapter tab : Chapter.values()) backgroundFiles.add("ovvar:wardrobe/" + tab.id + ".png");
		List<String> staticFiles = new ArrayList<>();
		for (WardrobeFont.Glyph glyph : WardrobeFont.glyphs()) staticFiles.add(glyph.textureRef());
		int backgrounds = 0, furniture = 0;
		for (JsonElement provider : font.getAsJsonArray("providers")) {
			JsonObject o = provider.getAsJsonObject();
			if (!o.get("type").getAsString().equals("bitmap")) continue;
			String file = o.get("file").getAsString();
			if (backgroundFiles.contains(file)) {
				backgrounds++;
				if (o.get("height").getAsInt() != WardrobeArt.HEIGHT) helper.fail("bitmap provider height is not " + WardrobeArt.HEIGHT + ": " + o);
			} else if (staticFiles.contains(file)) {
				furniture++;
			} else {
				helper.fail("a bitmap provider nothing claims: " + o);
			}
		}
		if (backgrounds != Chapter.values().length) helper.fail("expected one background per chapter (" + Chapter.values().length + "), font has " + backgrounds);
		if (furniture != WardrobeFont.glyphs().size()) helper.fail("the font lists " + furniture + " of its " + WardrobeFont.glyphs().size() + " glyphs");
		if (WardrobeFont.glyphs().size() < WardrobePreview.glyphCount()) helper.fail("the previews are not all registered with the font");
		// Every static glyph's art is the size the font promises for it, and the ascent puts its top
		// where the layout says (a cell-sized highlight on the tab row's own frame ring, and so on).
		for (WardrobeFont.Glyph glyph : WardrobeFont.glyphs()) {
			if (WardrobeFont.ascent(glyph.top()) != WardrobeFont.TITLE_Y + 7 - glyph.top()) helper.fail("the ascent formula moved under " + glyph.name());
		}

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

	/**
	 * <b>The rule that takes the whole font down with it.</b> A bitmap provider whose ascent is
	 * greater than its height is refused by the client, and a font with one bad provider in it is
	 * dropped entirely — every glyph of it — so the wardrobe falls back to a plain chest with a
	 * title of missing-glyph boxes. That is what shipped in v3: the stats readout is 5 px tall at
	 * the top of the header, where the ascent is 6.
	 *
	 * <p>So: every provider of the generated font, against every rule the client applies to it —
	 * ascent no greater than height, a texture that the same pack build actually wrote, of the
	 * height the provider claims, and one codepoint per glyph and no other.
	 */
	@GameTest
	public void wardrobeFontIsOneTheClientWillLoad(GameTestHelper helper) {
		JsonObject font = WardrobeArt.fontJson();
		Map<String, int[]> packed = WardrobeArt.packFiles();
		if (packed.isEmpty()) helper.fail("the resource pack was never built, so there is nothing to check the font against");
		Map<String, String> byChar = new java.util.LinkedHashMap<>();
		int providers = 0;
		for (JsonElement provider : font.getAsJsonArray("providers")) {
			JsonObject o = provider.getAsJsonObject();
			if (!o.get("type").getAsString().equals("bitmap")) continue;
			providers++;
			String file = o.get("file").getAsString();
			int ascent = o.get("ascent").getAsInt(), height = o.get("height").getAsInt();
			if (ascent > height) helper.fail("ascent " + ascent + " is higher than height " + height + " for " + file + ": the client refuses the whole font");
			int[] size = packed.get(WardrobeArt.texturePath(file));
			if (size == null) helper.fail("the font points at " + file + ", which this pack build never wrote");
			else if (size[1] != height) helper.fail(file + " is " + size[1] + " px tall, the provider says " + height);
			for (JsonElement row : o.getAsJsonArray("chars")) {
				String chars = row.getAsString();
				if (chars.length() != 1) helper.fail(file + " claims " + chars.length() + " codepoints in a row; one glyph, one codepoint");
				String already = byChar.put(chars, file);
				if (already != null) helper.fail("U+" + Integer.toHexString(chars.charAt(0)) + " is claimed by both " + already + " and " + file);
			}
		}
		if (providers != Chapter.values().length + WardrobeFont.glyphs().size()) {
			helper.fail(providers + " bitmap providers, wanted " + (Chapter.values().length + WardrobeFont.glyphs().size()));
		}
		// And no glyph's codepoint collides with the space provider's, which would draw a picture
		// where a space should be (or the other way round).
		for (char space : WardrobeFont.spaceAdvances().keySet()) {
			if (byChar.containsKey(String.valueOf(space))) helper.fail("U+" + Integer.toHexString(space) + " is both a space and " + byChar.get(String.valueOf(space)));
		}
		// The same rule at the source, so a new glyph cannot be added in a place that breaks it.
		for (WardrobeFont.Glyph glyph : WardrobeFont.glyphs()) {
			if (WardrobeFont.ascent(glyph.top()) > glyph.height()) {
				helper.fail(glyph.name() + " wants ascent " + WardrobeFont.ascent(glyph.top()) + " with height " + glyph.height());
			}
			// Padding a glyph to satisfy that must not have moved its art: the top row is still the
			// first row of the art, which is what every position on this screen is measured from.
			var art = glyph.art().get();
			if (art.height != glyph.height()) helper.fail(glyph.name() + " art is " + art.height + " px tall, the font says " + glyph.height());
		}
		helper.succeed();
	}

	/**
	 * "Bad overlap of textures": the background's drawn boxes must lie on the slot frames' own ring
	 * and never inside the 16×16 an item's icon fills, or every tab icon has a stitch line through
	 * it. Checked against the real checked-in template, every slot of the grid; and the corners of
	 * the boxes the template does draw are where the grid says (a box one px off would pass the
	 * overlap check by sitting in the gutter, so both are asserted).
	 */
	@GameTest
	public void wardrobeTemplateBoxesAreOnTheSlotGrid(GameTestHelper helper) {
		var template = WardrobeArt.readTemplate();
		for (int row = 0; row < 6; row++) {
			for (int col = 0; col < 9; col++) {
				int x0 = WardrobeFont.ITEM_X + WardrobeFont.PITCH * col, y0 = WardrobeFont.ITEM_Y + WardrobeFont.PITCH * row;
				for (int y = y0; y < y0 + WardrobeFont.ICON; y++) {
					for (int x = x0; x < x0 + WardrobeFont.ICON; x++) {
						if ((template.getRGB(x, y) & 0xFF) >= 250) {
							helper.fail("stitching at (" + x + "," + y + ") is inside the icon of slot (" + row + "," + col + ")");
						}
					}
				}
			}
		}
		// The tab row and the action row are drawn as one box per cell: their frame rings carry it.
		for (int col = 0; col < 9; col++) {
			for (int row : new int[]{0, 5}) {
				int x = WardrobeFont.cellX(col), y = WardrobeFont.cellY(row);
				if ((template.getRGB(x, y) & 0xFF) < 250) helper.fail("no box corner at the cell ring of slot (" + row + "," + col + "), (" + x + "," + y + ")");
			}
		}
		helper.succeed();
	}

	/**
	 * The title's own text is the chapter's name and nothing else, which fits the screen at the
	 * vanilla font's widest; the counts are pixel glyphs right-aligned in the spare header width,
	 * as many of them as fit beside that name, and the help item's lore has them all whatever
	 * happens. ("Text going over the limit": v2 put all of it in the title's text.)
	 */
	@GameTest
	public void wardrobeTitleTextFitsAndTheStatsAreGlyphs(GameTestHelper helper) {
		for (Chapter chapter : Chapter.values()) {
			String text = WardrobeGui.titleText(chapter);
			if (text.length() > 28) helper.fail(chapter + "'s title text is " + text.length() + " characters: \"" + text + "\"");
			if (WardrobeFont.TITLE_X + text.length() * WardrobeFont.TITLE_CHAR > WardrobeArt.WIDTH) {
				helper.fail(chapter + "'s title text can run off the right of the screen");
			}
			// Nothing of the counts is left in the text.
			for (String word : List.of("earned", "sewn", "stash")) {
				if (text.contains(word)) helper.fail(chapter + "'s title text still spells out the counts: \"" + text + "\"");
			}
		}

		Wardrobe wardrobe = Wardrobe.NONE.add(ITK_PATCH, 3).sew(CHAPTER, ITK).orElseThrow();
		String title = WardrobeGui.title(CHAPTER, wardrobe, Angle.FRONT).getString();
		for (WardrobeFont.Glyph label : List.of(WardrobeFont.EARNED, WardrobeFont.SEWN, WardrobeFont.STASH)) {
			if (title.indexOf(label.codepoint()) < 0) helper.fail("the title does not carry the " + label.name() + " readout");
		}
		// The digits of each count are there, and the row stays inside the header's right margin.
		if (title.indexOf(WardrobeFont.digit(3).codepoint()) < 0) helper.fail("the title does not carry the count 3 (earned) as a digit glyph");
		List<WardrobeFont.Glyph> row = WardrobeFont.statsRow(3, 1, 2, WardrobeGui.titleText(CHAPTER).length());
		if (row.isEmpty()) helper.fail("no room for any of the counts beside " + CHAPTER + "'s name");
		if (WardrobeFont.statsX(row, 0) < WardrobeFont.TITLE_X + WardrobeGui.titleText(CHAPTER).length() * WardrobeFont.TITLE_CHAR) {
			helper.fail("the stats readout starts on top of the title's own text");
		}
		int end = WardrobeFont.statsX(row, row.size() - 1) + row.get(row.size() - 1).width();
		if (end > WardrobeFont.HEADER_RIGHT) helper.fail("the stats readout ends at " + end + ", past the header's margin");

		// The longest chapter name there is still leaves room for at least one count.
		if (WardrobeFont.statsRow(1, 1, 1, WardrobeGui.titleChars()).isEmpty()) {
			helper.fail("the longest chapter name (" + WardrobeGui.titleChars() + " chars) leaves no room for any count");
		}
		helper.succeed();
	}

	/** The tab being shown gets the highlight glyph, placed at its own column; no tab, no highlight. */
	@GameTest
	public void wardrobeTitleHighlightsTheTabOnShow(GameTestHelper helper) {
		char highlight = WardrobeFont.ACTIVE_TAB.codepoint();
		for (int col = 0; col < WardrobeGui.TAB_COLS; col++) {
			String title = WardrobeGui.title(CHAPTER, Wardrobe.NONE, Angle.FRONT, col, true).getString();
			if (title.indexOf(highlight) < 0) helper.fail("no active-tab highlight in the title for tab " + col);
			String at = WardrobeFont.at(WardrobeFont.ACTIVE_TAB, WardrobeFont.cellX(col));
			if (!title.contains(at)) helper.fail("the highlight for tab " + col + " is not placed at x " + WardrobeFont.cellX(col));
		}
		if (WardrobeGui.title(CHAPTER, Wardrobe.NONE, Angle.FRONT, -1, true).getString().indexOf(highlight) >= 0) {
			helper.fail("a player on no tab of their own still gets a highlight");
		}
		helper.succeed();
	}

	// ---- the preview: a rendered picture of the player's own ovve

	/**
	 * Four sides, and every cell is seen from exactly one of them: the doll is built up out of a
	 * bare-ovve glyph per (chapter, angle) plus one glyph per (patch, cell), so the pack holds a
	 * fixed number of glyphs however much anybody sews. The count logged is the formula.
	 */
	@GameTest
	public void wardrobePreviewGlyphsAreFixedAndCoverEveryVisibleCell(GameTestHelper helper) {
		int bare = WardrobePreview.bareGlyphCount();
		if (bare != Chapter.values().length * Angle.values().length) {
			helper.fail("bare ovve glyphs: " + bare + ", wanted " + Chapter.values().length + " x " + Angle.values().length);
		}
		// One glyph per patch that fits a cell the doll can show, and none for a cell it cannot.
		int wanted = 0, hidden = 0;
		for (Spot spot : Spot.values()) {
			Angle angle = WardrobePreview.angleOf(spot);
			if (angle == null) {
				hidden++;
				continue;
			}
			// A cell is on one face of one box, and a face is seen from one side only.
			int sides = 0;
			for (Angle other : Angle.values()) if (WardrobePreview.angleOf(spot) == other) sides++;
			if (sides != 1) helper.fail(spot + " is drawn from " + sides + " sides, wanted exactly one");
			for (Patches.Patch patch : Patches.all()) {
				if (!patch.fits(spot)) continue;
				wanted++;
				Placement placement = new Placement(spot, patch);
				if (WardrobePreview.patchGlyph(placement) == null) helper.fail("no glyph for " + placement.key() + " on the " + angle + " view");
			}
		}
		if (WardrobePreview.patchGlyphCount() != wanted) {
			helper.fail("patch glyphs: " + WardrobePreview.patchGlyphCount() + ", wanted " + wanted);
		}
		// The inner faces are the only cells the doll never shows, and there are none of those.
		if (hidden != 0) helper.fail(hidden + " cell(s) are on no view at all");
		helper.succeed();
	}

	/**
	 * A placement is drawn by its own glyph, in the title, over the bare ovve of the angle that
	 * shows its cell — and by nothing at all at the other three angles.
	 */
	@GameTest
	public void wardrobeTitleStacksTheBareOvveAndEveryPlacement(GameTestHelper helper) {
		Placement sleeve = new Placement(Spot.SLEEVE_OUT_MID_R, NYCKELN_PATCH);
		Wardrobe wardrobe = Wardrobe.NONE.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElseThrow()
				.add(sleeve.patch(), 1).sew(CHAPTER, sleeve).orElseThrow();
		for (Angle angle : Angle.values()) {
			String title = WardrobeGui.title(CHAPTER, wardrobe, angle).getString();
			char barely = WardrobePreview.bareGlyph(CHAPTER, angle).codepoint();
			if (title.indexOf(barely) < 0) helper.fail("the " + angle + " title does not draw the bare ovve");
			for (Placement placement : List.of(ITK, sleeve)) {
				WardrobeFont.Glyph glyph = WardrobePreview.patchGlyph(placement);
				boolean shown = WardrobePreview.angleOf(placement.spot()) == angle;
				boolean drawn = title.indexOf(glyph.codepoint()) >= 0;
				if (shown && !drawn) helper.fail(placement.key() + " is not drawn on the " + angle + " view, which shows its cell");
				if (!shown && drawn) helper.fail(placement.key() + " is drawn on the " + angle + " view, which cannot show its cell");
				if (shown && !title.contains(WardrobeFont.at(glyph))) helper.fail(placement.key() + " is not placed where the doll draws it");
			}
			// The chest is seen from the front and the sleeve's outer face from the wearer's right.
			if (angle == Angle.FRONT && WardrobePreview.angleOf(ITK.spot()) != Angle.FRONT) helper.fail("the chest is not on the front view");
			if (WardrobePreview.angleOf(sleeve.spot()) != Angle.RIGHT) helper.fail("a right sleeve's outer face is not on the right-side view");
		}
		helper.succeed();
	}

	/** Every glyph's art is the size the font promises, and every patch glyph lands inside the preview panel. */
	@GameTest
	public void wardrobePreviewArtFitsThePanel(GameTestHelper helper) {
		for (WardrobeFont.Glyph glyph : WardrobeFont.glyphs()) {
			var art = glyph.art().get();
			if (art.width != glyph.width() || art.height != glyph.height()) {
				helper.fail(glyph.name() + " art is " + art.width + "x" + art.height + ", the font says " + glyph.width() + "x" + glyph.height());
			}
			if (!glyph.name().startsWith("preview/")) continue;
			if (glyph.x() < WardrobePreview.PANEL_X || glyph.x() + glyph.width() > WardrobePreview.PANEL_X + WardrobePreview.PANEL_W
					|| glyph.top() < WardrobePreview.PANEL_Y || glyph.top() + glyph.height() > WardrobePreview.PANEL_Y + WardrobePreview.PANEL_H) {
				helper.fail(glyph.name() + " is drawn outside the preview panel, at (" + glyph.x() + "," + glyph.top() + ") " + glyph.width() + "x" + glyph.height());
			}
		}
		helper.succeed();
	}

	/** The rotation buttons are at the end of the tab row and cycle the angle through all four sides. */
	@GameTest(maxTicks = 1200)
	public void wardrobeRotationButtonsCycleTheAngle(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		// The screen only builds its rows once the player's wardrobe is in the cache — before that
		// it is the "loading" screen — and the fetch goes to whichever throwaway backend is in.
		Wardrobes.fetch(player.getUUID());
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(player.getUUID()), "player not loaded"))
				.thenExecute(() -> rotationButtons(helper, player))
				.thenSucceed();
	}

	private static void rotationButtons(GameTestHelper helper, ServerPlayer player) {
		for (Angle angle : Angle.values()) {
			WardrobeGui gui = WardrobeGui.forTest(player, CHAPTER, angle);
			if (gui.angle() != angle) helper.fail("the screen is at " + gui.angle() + ", wanted " + angle);
			for (int slot : new int[]{WardrobeGui.ROTATE_LEFT, WardrobeGui.ROTATE_RIGHT}) {
				if (isEmpty(gui, slot)) helper.fail("no rotation button at slot " + slot);
				String name = gui.getGuiElement(slot).getItemStack().getHoverName().getString();
				if (!name.toLowerCase(java.util.Locale.ROOT).contains("turn")) helper.fail("the button at " + slot + " reads \"" + name + "\"");
			}
			String lore = lore(gui.getGuiElement(WardrobeGui.ROTATE_RIGHT).getItemStack());
			if (!lore.contains(angle.label)) helper.fail("the right-turn button does not say which side is showing: " + lore);
			if (!lore.contains(angle.turned(1).label)) helper.fail("the right-turn button does not say which side it brings round: " + lore);
		}
		// Four steps either way come back to where they started, and no two sides are the same.
		Angle at = Angle.FRONT;
		for (int i = 0; i < 4; i++) at = at.turned(1);
		if (at != Angle.FRONT) helper.fail("turning right four times does not come back to the front");
		for (int i = 0; i < 4; i++) at = at.turned(-1);
		if (at != Angle.FRONT) helper.fail("turning left four times does not come back to the front");
		if (Angle.FRONT.turned(1) == Angle.FRONT.turned(-1)) helper.fail("turning left and right are the same move");
	}

	/** The hover tooltips are the front view's only: from a side they would point at the wrong part of the figure. */
	@GameTest(maxTicks = 1200)
	public void wardrobePreviewTooltipsAreTheFrontViewsOnly(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		UUID owner = player.getUUID();
		AtomicReference<Wardrobes.Outcome> outcome = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> guarded(server, () -> {
					Wardrobes.use(server, new FileBackend(dir));
					Wardrobes.fetch(owner);
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(owner), "owner not loaded"))
				.thenExecute(() -> Wardrobes.update(owner, w -> w.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElse(null), outcome::set))
				.thenWaitUntil(() -> assertThat(outcome.get() == Wardrobes.Outcome.OK, "setup outcome " + outcome.get()))
				.thenExecute(() -> guarded(server, () -> {
					WardrobeGui front = WardrobeGui.forTest(player, CHAPTER, Angle.FRONT);
					int slot = WardrobeGui.previewSlot(ITK.spot());
					if (isEmpty(front, slot)) helper.fail("no tooltip item at the ITK's slot on the front view");
					if (front.getGuiElement(slot).getGuiCallback() != GuiElement.EMPTY_CALLBACK) helper.fail("a preview item has a click callback");
					for (Angle angle : new Angle[]{Angle.RIGHT, Angle.BACK, Angle.LEFT}) {
						WardrobeGui turned = WardrobeGui.forTest(player, CHAPTER, angle);
						for (int i = 9; i < 45; i++) {
							int col = i % 9;
							if (col >= 5 && !isEmpty(turned, i)) helper.fail("a tooltip item is left over the preview on the " + angle + " view");
						}
					}
					release(server);
				}))
				.thenSucceed();
	}

	/**
	 * {@code /ovvar look <player>}: somebody else's ovve, read-only. Their design drives the preview
	 * and its tooltips, their stash is not shown at all, and the action row has nothing on it but
	 * help and close — no take out, no put in, no sewing, no mannequin, and no click anywhere.
	 */
	@GameTest(maxTicks = 1200)
	public void wardrobeLookIsReadOnlyAndShowsTheirDesign(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = Files.createTempDirectory("ovvar-wardrobes");
		ServerPlayer viewer = helper.makeMockServerPlayerInLevel();
		UUID them = UUID.randomUUID();
		Placement sleeve = new Placement(Spot.SLEEVE_OUT_MID_R, NYCKELN_PATCH);
		helper.startSequence()
				.thenWaitUntil(() -> assertThat(BUSY.compareAndSet(false, true), "another store test is running"))
				.thenExecute(() -> guarded(server, () -> {
					try {
						// Their wardrobe, written as if by another server, then loaded by UUID: a
						// wardrobe is a row in a store, so they need not be here at all.
						FileBackend backend = new FileBackend(dir);
						Wardrobe theirs = Wardrobe.NONE.add(ITK_PATCH, 1).sew(CHAPTER, ITK).orElseThrow()
								.add(sleeve.patch(), 1).sew(CHAPTER, sleeve).orElseThrow()
								.add(NYCKELN_PATCH, 4).withVersion(1);
						if (!backend.store(them, theirs, 0)) helper.fail("could not write the other player's wardrobe");
						Wardrobes.use(server, new FileBackend(dir));
						Wardrobes.fetch(them);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}))
				.thenWaitUntil(() -> assertThat(Wardrobes.loaded(them), "the other player's wardrobe never loaded"))
				.thenExecute(() -> guarded(server, () -> {
					Wardrobe theirs = Wardrobes.current(them);
					if (WardrobeGui.sewnCount(theirs, CHAPTER) != 2) helper.fail("their design did not load: " + theirs);
					WardrobeGui gui = WardrobeGui.forTestLook(viewer, them, "Nisse", CHAPTER, Angle.FRONT);
					if (gui.own()) helper.fail("a look at somebody else says it is the viewer's own");

					// The action row: help and close, and nothing else, none of it clickable but close.
					for (int slot = 45; slot < 54; slot++) {
						boolean allowed = slot == 52 || slot == 53;
						if (!allowed && !isEmpty(gui, slot)) {
							helper.fail("action slot " + slot + " is filled on a read-only look: " + gui.getGuiElement(slot).getItemStack());
						}
					}
					if (isEmpty(gui, 52)) helper.fail("no help item on a read-only look");
					if (isEmpty(gui, 53)) helper.fail("no close button on a read-only look");
					if (!gui.getGuiElement(52).getItemStack().getHoverName().getString().contains("Nisse")) {
						helper.fail("the help item does not say whose ovve this is: " + gui.getGuiElement(52).getItemStack().getHoverName().getString());
					}

					// Their stash is not shown, and the pocket says why.
					for (int slot = 9; slot < 45; slot++) {
						int col = slot % 9;
						if (col < 5 && !isEmpty(gui, slot)) helper.fail("their stash is on show at slot " + slot);
					}
					String title = gui.getTitle().getString();
					if (title.indexOf(WardrobeFont.LOOK_ONLY.codepoint()) < 0) helper.fail("the pocket does not say it is a look only");
					if (title.indexOf(WardrobeFont.NO_PATCHES.codepoint()) >= 0) helper.fail("a look draws the \"no patches yet\" notice over their stash");

					// Their design drives the preview: the bare ovve and both of their placements.
					if (title.indexOf(WardrobePreview.bareGlyph(CHAPTER, Angle.FRONT).codepoint()) < 0) helper.fail("no bare ovve on a look");
					if (title.indexOf(WardrobePreview.patchGlyph(ITK).codepoint()) < 0) helper.fail("their chest patch is not drawn on the front view");
					int slot = WardrobeGui.previewSlot(ITK.spot());
					if (isEmpty(gui, slot)) helper.fail("no tooltip for their patch");
					if (gui.getGuiElement(slot).getGuiCallback() != GuiElement.EMPTY_CALLBACK) helper.fail("a look's preview item is clickable");
					if (!lore(gui.getGuiElement(slot).getItemStack()).contains("Nisse")) {
						helper.fail("the tooltip does not say whose ovve the patch is on: " + lore(gui.getGuiElement(slot).getItemStack()));
					}

					// The tabs are the chapters they have sewn on, and they are clickable (turning and
					// switching chapters is all a look can do).
					if (isEmpty(gui, 0)) helper.fail("no tab for the chapter they have a design for");
					if (isEmpty(gui, WardrobeGui.ROTATE_LEFT) || isEmpty(gui, WardrobeGui.ROTATE_RIGHT)) helper.fail("a look cannot turn the figure");
					release(server);
				}))
				.thenSucceed();
	}

	private static boolean isEmpty(WardrobeGui gui, int slot) {
		GuiElement element = gui.getGuiElement(slot);
		return element == null || element.getItemStack().isEmpty();
	}

	/** {@code /ovvar look} with no name is a look at your own, and with a name resolves a player the server has seen. */
	@GameTest
	public void lookCommandOpensAReadOnlyWardrobe(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		MinecraftServer server = helper.getLevel().getServer();
		var before = player.containerMenu;
		server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "ovvar look");
		if (player.containerMenu == before) helper.fail("/ovvar look did not open a menu");
		var own = player.containerMenu;
		server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "ovvar look " + player.getName().getString());
		if (player.containerMenu == own) helper.fail("/ovvar look <player> did not open a menu");
		// Whether a name resolves at all is the server's own business (its profile resolver): on an
		// online-mode server an unknown name is refused before this command runs, and on an
		// offline-mode one it resolves to an offline UUID, which simply has no wardrobe.
		helper.succeed();
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

	/** A throwaway store back (never the run dir's configured one), and the next test may go. */
	private static void release(MinecraftServer server) {
		Wardrobes.use(server, idleBackend());
		BUSY.set(false);
	}

	private static void assertThat(boolean condition, String message) {
		if (!condition) throw new GameTestAssertException(Component.literal(message), 0);
	}
}
