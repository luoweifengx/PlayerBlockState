package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TerritoryDailyProcessorNewlyOwnedTest {
	private static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

	@Test
	void naturalToOccupiedSameOrgIsNewlyOwned() {
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.NATURAL, null, ChunkState.OCCUPIED, ORG_A));
	}

	@Test
	void naturalToBorderSameOrgIsNewlyOwned() {
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.NATURAL, null, ChunkState.BORDER, ORG_A));
	}

	@Test
	void hostileDeathSafeToOccupiedFamilyIsNewlyOwned() {
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.HOSTILE_BORDER, null, ChunkState.OCCUPIED, ORG_A));
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.HOSTILE, null, ChunkState.OCCUPIED, ORG_A));
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.DEATH, null, ChunkState.BORDER, ORG_A));
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.SAFE, null, ChunkState.OCCUPIED, ORG_A));
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.DEMON, null, ChunkState.BORDER, ORG_A));
	}

	@Test
	void otherOccupiedToOwnOccupiedFamilyIsNewlyOwned() {
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.OCCUPIED, ORG_B, ChunkState.OCCUPIED, ORG_A));
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.OCCUPIED, ORG_B, ChunkState.BORDER, ORG_A));
		assertTrue(TerritoryDailyProcessor.isNewlyOwned(ChunkState.BORDER, ORG_B, ChunkState.OCCUPIED, ORG_A));
	}

	@Test
	void ownOccupiedFamilyBorderSwapIsNotNewlyOwned() {
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.OCCUPIED, ORG_A, ChunkState.BORDER, ORG_A));
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.BORDER, ORG_A, ChunkState.OCCUPIED, ORG_A));
	}

	@Test
	void ownOccupiedFamilyUnchangedIsNotNewlyOwned() {
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.OCCUPIED, ORG_A, ChunkState.OCCUPIED, ORG_A));
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.BORDER, ORG_A, ChunkState.BORDER, ORG_A));
	}

	@Test
	void nullNewOrgOrNonOccupiedFamilyIsNotNewlyOwned() {
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.NATURAL, null, ChunkState.OCCUPIED, null));
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.OCCUPIED, ORG_A, ChunkState.NATURAL, ORG_A));
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.NATURAL, null, ChunkState.HOSTILE_BORDER, ORG_A));
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.NATURAL, null, ChunkState.HOSTILE, ORG_A));
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.OCCUPIED, ORG_A, ChunkState.DEATH, ORG_A));
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.NATURAL, null, ChunkState.SAFE, ORG_A));
		assertFalse(TerritoryDailyProcessor.isNewlyOwned(ChunkState.NATURAL, null, ChunkState.DEMON, ORG_A));
	}
}
