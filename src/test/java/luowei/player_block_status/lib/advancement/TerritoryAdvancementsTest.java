package luowei.player_block_status.lib.advancement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerritoryAdvancementsTest {
	@Test
	void homeRequiresOwnedChunks() {
		assertFalse(TerritoryAdvancements.shouldGrantHome(0));
		assertTrue(TerritoryAdvancements.shouldGrantHome(1));
		assertTrue(TerritoryAdvancements.shouldGrantHome(8));
	}

	@Test
	void beaconEndOnlyOnFirstCrossingToLevel3() {
		assertTrue(TerritoryAdvancements.shouldGrantBeaconEnd(0, 3, false));
		assertTrue(TerritoryAdvancements.shouldGrantBeaconEnd(2, 3, false));
		assertTrue(TerritoryAdvancements.shouldGrantBeaconEnd(0, 4, false));
		assertFalse(TerritoryAdvancements.shouldGrantBeaconEnd(2, 3, true));
		assertFalse(TerritoryAdvancements.shouldGrantBeaconEnd(3, 3, false));
		assertFalse(TerritoryAdvancements.shouldGrantBeaconEnd(3, 4, false));
		assertFalse(TerritoryAdvancements.shouldGrantBeaconEnd(4, 2, false));
		assertFalse(TerritoryAdvancements.shouldGrantBeaconEnd(2, 2, false));
	}
}
