package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import luowei.player_block_status.lib.api.OrganizationProvider;

class ChunkScoreEngineTest {
	private static final UUID PLAYER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UUID PLAYER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
	private static final UUID ORG_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

	@BeforeEach
	@AfterEach
	void restoreTerritoryConfigDefaults() {
		TerritoryConfig.blockScorePerBlock = 4;
	}

	@Test
	void nPlayerBlocksScoreNTimesBlockScorePerBlock() {
		Map<Long, UUID> placed = new HashMap<>();
		placed.put(1L, PLAYER_A);
		placed.put(2L, PLAYER_A);
		placed.put(3L, PLAYER_A);
		placed.put(4L, PLAYER_B);

		Map<UUID, Integer> totals = ChunkScoreEngine.computeTotalScores(
				placed,
				Map.of(),
				Map.of(),
				OrganizationProvider.NONE
		);

		assertEquals(3 * TerritoryConfig.blockScorePerBlock, totals.get(PLAYER_A));
		assertEquals(TerritoryConfig.blockScorePerBlock, totals.get(PLAYER_B));
		assertEquals(2, totals.size());
	}

	@Test
	void structureSentinelBlocksDoNotScore() {
		Map<Long, UUID> placed = new HashMap<>();
		placed.put(1L, PLAYER_A);
		placed.put(2L, TerritoryConfig.STRUCTURE_BLOCK_SENTINEL);
		placed.put(3L, TerritoryConfig.STRUCTURE_BLOCK_SENTINEL);

		Map<UUID, Integer> totals = ChunkScoreEngine.computeTotalScores(
				placed,
				Map.of(),
				Map.of(),
				OrganizationProvider.NONE
		);

		assertEquals(TerritoryConfig.blockScorePerBlock, totals.get(PLAYER_A));
		assertFalse(totals.containsKey(TerritoryConfig.STRUCTURE_BLOCK_SENTINEL));
		assertEquals(1, totals.size());
	}

	@Test
	void stayScoresAndDeathModifiersMergeIntoTotal() {
		Map<Long, UUID> placed = new HashMap<>();
		placed.put(1L, PLAYER_A);
		placed.put(2L, PLAYER_A);

		Map<UUID, Integer> totals = ChunkScoreEngine.computeTotalScores(
				placed,
				Map.of(PLAYER_A, 50, PLAYER_B, 12),
				Map.of(PLAYER_A, -20, PLAYER_B, TerritoryConfig.deathPenalty),
				OrganizationProvider.NONE
		);

		assertEquals(2 * TerritoryConfig.blockScorePerBlock + 50 - 20, totals.get(PLAYER_A));
		assertEquals(12 + TerritoryConfig.deathPenalty, totals.get(PLAYER_B));
	}

	@Test
	void organizationProviderIsIgnoredForScoreKeys() {
		OrganizationProvider alwaysOrg = (server, playerId) -> Optional.of(ORG_ID);
		Map<Long, UUID> placed = new HashMap<>();
		placed.put(1L, PLAYER_A);
		placed.put(2L, PLAYER_B);

		Map<UUID, Integer> totals = ChunkScoreEngine.computeTotalScores(
				placed,
				Map.of(PLAYER_A, 10),
				Map.of(PLAYER_B, -5),
				alwaysOrg
		);

		assertTrue(totals.containsKey(PLAYER_A));
		assertTrue(totals.containsKey(PLAYER_B));
		assertFalse(totals.containsKey(ORG_ID));
		assertEquals(TerritoryConfig.blockScorePerBlock + 10, totals.get(PLAYER_A));
		assertEquals(TerritoryConfig.blockScorePerBlock - 5, totals.get(PLAYER_B));
	}
}
