package nu.metacraft.rivals.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

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
}
