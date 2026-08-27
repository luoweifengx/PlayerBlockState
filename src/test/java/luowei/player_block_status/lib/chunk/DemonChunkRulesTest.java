package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemonChunkRulesTest {
	@Test
	void defaultFlagsWhenNoBeaconsAndNoPreviousBeaconsDoesNotReset() {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(0, 0, 0, 0);

		assertFalse(result.fireReset());
		assertFalse(result.clearAllDemonChunks());
		assertEquals(TerritoryConfig.DEMON_SPREAD_PROBABILITY_DEFAULT, result.flags().spreadProbability());
		assertTrue(result.flags().spreadingEnabled());
		assertFalse(result.flags().generationForbidden());
	}

	@Test
	void resetOnlyWhenCountHitsZeroFromPositive() {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(0, 0, 2, 3);

		assertTrue(result.fireReset());
		assertFalse(result.clearAllDemonChunks());
		assertEquals(TerritoryConfig.DEMON_SPREAD_PROBABILITY_DEFAULT, result.flags().spreadProbability());
		assertTrue(result.flags().spreadingEnabled());
		assertFalse(result.flags().generationForbidden());
	}

	@Test
	void level1SetsSpreadProbabilityToThousandth() {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(1, 1, 0, 0);

		assertFalse(result.fireReset());
		assertFalse(result.clearAllDemonChunks());
		assertEquals(TerritoryConfig.DEMON_SPREAD_PROBABILITY_LEVEL1, result.flags().spreadProbability());
		assertTrue(result.flags().spreadingEnabled());
		assertFalse(result.flags().generationForbidden());
	}

	@Test
	void level2StopsSpreadingWithoutForbiddingGeneration() {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(1, 2, 1, 1);

		assertFalse(result.fireReset());
		assertFalse(result.clearAllDemonChunks());
		assertEquals(0.0d, result.flags().spreadProbability());
		assertFalse(result.flags().spreadingEnabled());
		assertFalse(result.flags().generationForbidden());
	}

	@Test
	void level3ClearsAndForbidsGenerationWhenBecomingActive() {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(1, 3, 1, 1);

		assertFalse(result.fireReset());
		assertTrue(result.clearAllDemonChunks());
		assertFalse(result.flags().spreadingEnabled());
		assertTrue(result.flags().generationForbidden());
	}

	@Test
	void level3AlreadyActiveDoesNotClearAgain() {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(2, 3, 1, 3);

		assertFalse(result.fireReset());
		assertFalse(result.clearAllDemonChunks());
		assertTrue(result.flags().generationForbidden());
	}

	@Test
	void losingLevel3WhileLevel1RemainsFallsBackWithoutReset() {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(1, 1, 2, 3);

		assertFalse(result.fireReset());
		assertFalse(result.clearAllDemonChunks());
		assertEquals(TerritoryConfig.DEMON_SPREAD_PROBABILITY_LEVEL1, result.flags().spreadProbability());
		assertTrue(result.flags().spreadingEnabled());
		assertFalse(result.flags().generationForbidden());
	}

	@Test
	void losingLevel3WhileLevel2RemainsStopsSpreadWithoutDestroyingPortals() {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(1, 2, 2, 3);

		assertFalse(result.fireReset());
		assertFalse(result.clearAllDemonChunks());
		assertFalse(result.flags().spreadingEnabled());
		assertFalse(result.flags().generationForbidden());
	}

	@Test
	void level4TreatedAsLevel3() {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(1, 4, 0, 0);

		assertTrue(result.clearAllDemonChunks());
		assertTrue(result.flags().generationForbidden());
	}

	@Test
	void portalGenerationAllowedUnlessForbidden() {
		assertTrue(DemonChunkRules.shouldCreateDemonFromPortal(false));
		assertFalse(DemonChunkRules.shouldCreateDemonFromPortal(true));
	}
}
