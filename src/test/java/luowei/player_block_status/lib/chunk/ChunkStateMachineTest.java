package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChunkStateMachineTest {
	private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CHALLENGER = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@BeforeEach
	@AfterEach
	void restoreTerritoryConfigDefaults() {
		TerritoryConfig.occupationThreshold = 1000;
		TerritoryConfig.naturalReturnThreshold = 500;
		TerritoryConfig.deathThreshold = -100;
		TerritoryConfig.occupationTakeoverMultiplier = 2.5;
		TerritoryConfig.borderTakeoverMultiplier = 1.25;
		TerritoryConfig.hostileBorderExtensionChunks = 2;
		TerritoryConfig.deathRecoveryPerDay = 30;
	}

	@Test
	void naturalJustBelowOccupationThresholdStaysNatural() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.NATURAL, null, false, Map.of(OWNER, 999))
		);

		assertEquals(ChunkState.NATURAL, result.state());
		assertNull(result.occupyingOrg());
	}

	@Test
	void naturalAtOccupationThresholdBecomesOccupied() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.NATURAL, null, false, Map.of(OWNER, 1000))
		);

		assertEquals(ChunkState.OCCUPIED, result.state());
		assertEquals(OWNER, result.occupyingOrg());
	}

	@Test
	void occupiedAllBelowNaturalReturnBecomesNaturalAndClearsOrg() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.OCCUPIED, OWNER, false, Map.of(OWNER, 499, CHALLENGER, 0))
		);

		assertEquals(ChunkState.NATURAL, result.state());
		assertNull(result.occupyingOrg());
	}

	@Test
	void occupiedSomeoneAtNaturalReturnStaysOccupied() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.OCCUPIED, OWNER, false, Map.of(OWNER, 500))
		);

		assertEquals(ChunkState.OCCUPIED, result.state());
		assertEquals(OWNER, result.occupyingOrg());
	}

	@Test
	void borderAllBelowNaturalReturnBecomesNatural() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.BORDER, OWNER, false, Map.of(OWNER, 499))
		);

		assertEquals(ChunkState.NATURAL, result.state());
		assertNull(result.occupyingOrg());
	}

	@Test
	void hostileBorderBelowOccupationKeepsHostileBorder() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.HOSTILE_BORDER, null, false, Map.of(OWNER, 999))
		);

		assertEquals(ChunkState.HOSTILE_BORDER, result.state());
		assertNull(result.occupyingOrg());
	}

	@Test
	void occupiedTakeoverJustBelowMultiplierFails() {
		int ownerScore = 1000;
		int required = (int) Math.ceil(ownerScore * TerritoryConfig.occupationTakeoverMultiplier);

		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.OCCUPIED, OWNER, false, Map.of(OWNER, ownerScore, CHALLENGER, required - 1))
		);

		assertEquals(ChunkState.OCCUPIED, result.state());
		assertEquals(OWNER, result.occupyingOrg());
	}

	@Test
	void occupiedTakeoverAtMultiplierChangesOwner() {
		int ownerScore = 1000;
		int required = (int) Math.ceil(ownerScore * TerritoryConfig.occupationTakeoverMultiplier);

		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.OCCUPIED, OWNER, false, Map.of(OWNER, ownerScore, CHALLENGER, required))
		);

		assertEquals(ChunkState.OCCUPIED, result.state());
		assertEquals(CHALLENGER, result.occupyingOrg());
	}

	@Test
	void borderTakeoverJustBelowMultiplierFails() {
		int ownerScore = 800;
		int required = (int) Math.ceil(ownerScore * TerritoryConfig.borderTakeoverMultiplier);

		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.BORDER, OWNER, false, Map.of(OWNER, ownerScore, CHALLENGER, required - 1))
		);

		assertEquals(ChunkState.OCCUPIED, result.state());
		assertEquals(OWNER, result.occupyingOrg());
	}

	@Test
	void borderTakeoverAtMultiplierChangesOwner() {
		int ownerScore = 800;
		int required = (int) Math.ceil(ownerScore * TerritoryConfig.borderTakeoverMultiplier);

		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.BORDER, OWNER, false, Map.of(OWNER, ownerScore, CHALLENGER, required))
		);

		assertEquals(ChunkState.OCCUPIED, result.state());
		assertEquals(CHALLENGER, result.occupyingOrg());
	}

	@Test
	void safeChunkWithoutDeathScoreIsSafe() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.NATURAL, null, true, Map.of(OWNER, 2000))
		);

		assertEquals(ChunkState.SAFE, result.state());
	}

	@Test
	void safeChunkWithDeathScoreIsDeath() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.SAFE, null, true, Map.of(OWNER, TerritoryConfig.deathThreshold))
		);

		assertEquals(ChunkState.DEATH, result.state());
	}

	@Test
	void deathScoreTakesPriorityOverOccupation() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(
						ChunkState.NATURAL,
						null,
						false,
						Map.of(OWNER, 2000, CHALLENGER, TerritoryConfig.deathThreshold)
				)
		);

		assertEquals(ChunkState.DEATH, result.state());
	}

	@Test
	void demonIsNotReplacedByOccupationScores() {
		TerritoryDailyProcessor.BaseStateResult result = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.DEMON, OWNER, false, Map.of(OWNER, 5000))
		);

		assertEquals(ChunkState.DEMON, result.state());
		assertEquals(OWNER, result.occupyingOrg());
	}

	@Test
	void demonIsNotReplacedBySafeOrDeath() {
		TerritoryDailyProcessor.BaseStateResult death = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.DEMON, null, true, Map.of(OWNER, TerritoryConfig.deathThreshold))
		);
		assertEquals(ChunkState.DEMON, death.state());

		TerritoryDailyProcessor.BaseStateResult safe = ChunkStateMachine.computeBaseStateFromSnapshot(
				snapshot(ChunkState.DEMON, null, true, Map.of(OWNER, 2000))
		);
		assertEquals(ChunkState.DEMON, safe.state());
	}

	@Test
	void demonCannotBeReplacedByOtherStatesButOverwritesThem() {
		assertFalse(ChunkState.DEMON.canBeReplacedBy(ChunkState.OCCUPIED));
		assertFalse(ChunkState.DEMON.canBeReplacedBy(ChunkState.NATURAL));
		assertFalse(ChunkState.DEMON.canBeReplacedBy(ChunkState.HOSTILE_BORDER));
		assertTrue(ChunkState.DEMON.canBeReplacedBy(ChunkState.DEMON));
		assertTrue(ChunkState.OCCUPIED.canBeReplacedBy(ChunkState.DEMON));
		assertTrue(ChunkState.NATURAL.canBeReplacedBy(ChunkState.DEMON));
		assertTrue(ChunkState.BORDER.canBeReplacedBy(ChunkState.DEMON));
	}

	@Test
	void demonStateIdRoundTrips() {
		assertEquals(7, ChunkState.DEMON.getId());
		assertEquals(ChunkState.DEMON, ChunkState.fromId(7));
	}

	private static TerritoryDailyProcessor.DailyChunkSnapshot snapshot(
			ChunkState previousState,
			UUID occupyingOrg,
			boolean safeChunk,
			Map<UUID, Integer> cachedScores
	) {
		return new TerritoryDailyProcessor.DailyChunkSnapshot(
				0L,
				Map.of(),
				Map.of(),
				Map.of(),
				previousState,
				occupyingOrg,
				safeChunk,
				new HashMap<>(cachedScores)
		);
	}
}
