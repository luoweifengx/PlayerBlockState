package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

class InfectionTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	void restoreInterval() {
		TerritoryConfig.refreshIntervalTicks = 3000;
	}

	@Test
	void scheduledTickIsHalfRefreshOffset() {
		assertFalse(Infection.isScheduledTick(0));
		assertTrue(Infection.isScheduledTick(1500));
		assertFalse(Infection.isScheduledTick(3000));
		assertTrue(Infection.isScheduledTick(4500));
		assertFalse(Infection.isScheduledTick(1501));
	}

	@Test
	void skipWhenOccupiedOverThresholdWithoutStronghold() {
		Map<Long, ChunkState> states = new HashMap<>();
		long border = key(0, 0);
		states.put(border, ChunkState.BORDER);
		int occupied = 0;
		for (long cell : Infection.chebyshevKeys(border, Infection.CHEBYSHEV)) {
			if (cell == border) {
				continue;
			}
			if (occupied < 36) {
				states.put(cell, ChunkState.OCCUPIED);
				occupied++;
			}
		}
		long wilderness = key(3, 0);
		states.put(wilderness, ChunkState.NATURAL);

		Infection.InfectionResult result = Infection.run(
				Infection.mapGrid(states),
				Set.of(border),
				new ScriptedRandom(0.0)
		);

		assertTrue(result.skippedBorders().contains(border));
		assertEquals(ChunkState.NATURAL, states.getOrDefault(wilderness, ChunkState.NATURAL));
		assertTrue(result.changed().isEmpty());
	}

	@Test
	void doesNotSkipWhenOccupiedOverThresholdButHasDemon() {
		Map<Long, ChunkState> states = new HashMap<>();
		long border = key(0, 0);
		states.put(border, ChunkState.BORDER);
		int occupied = 0;
		for (long cell : Infection.chebyshevKeys(border, Infection.CHEBYSHEV)) {
			if (cell == border) {
				continue;
			}
			if (occupied < 36) {
				states.put(cell, ChunkState.OCCUPIED);
				occupied++;
			}
		}
		states.put(key(3, 0), ChunkState.DEMON);
		long target = key(3, 1);
		states.put(target, ChunkState.NATURAL);

		Infection.run(Infection.mapGrid(states), Set.of(border), new ScriptedRandom(0.0));

		assertEquals(ChunkState.HOSTILE_BORDER, states.get(target));
	}

	@Test
	void convertUsesEightPercentWithoutStronghold() {
		Map<Long, ChunkState> states = borderWithNaturalTarget();
		long target = key(1, 0);

		Infection.run(Infection.mapGrid(states), Set.of(key(0, 0)), rolls(0.0, 0.3));

		assertEquals(ChunkState.NATURAL, states.get(target));
	}

	@Test
	void convertPaintsHostileBorderNotHostileOccupied() {
		Map<Long, ChunkState> states = borderWithNaturalTarget();
		long target = key(1, 0);

		Infection.run(Infection.mapGrid(states), Set.of(key(0, 0)), new ScriptedRandom(0.0));

		assertEquals(ChunkState.HOSTILE_BORDER, states.get(target));
	}

	@Test
	void convertUsesFortyPercentWhenStrongholdPresent() {
		Map<Long, ChunkState> states = borderWithNaturalTarget();
		states.put(key(2, 0), ChunkState.DEMON);
		long target = key(1, 0);

		Infection.run(Infection.mapGrid(states), Set.of(key(0, 0)), rolls(0.0, 0.3));

		assertEquals(ChunkState.HOSTILE_BORDER, states.get(target));
	}

	@Test
	void convertUsesFortyPercentWhenHostileOccupiedCountExceedsTen() {
		Map<Long, ChunkState> states = borderWithNaturalTarget();
		int hostile = 0;
		for (long cell : Infection.chebyshevKeys(key(0, 0), Infection.CHEBYSHEV)) {
			if (cell == key(0, 0) || cell == key(1, 0)) {
				continue;
			}
			if (hostile < 11) {
				states.put(cell, ChunkState.HOSTILE);
				hostile++;
			}
		}
		long target = key(1, 0);

		Infection.run(Infection.mapGrid(states), Set.of(key(0, 0)), rolls(0.0, 0.3));

		assertEquals(ChunkState.HOSTILE_BORDER, states.get(target));
	}

	@Test
	void safeChunkDoesNotChange() {
		Map<Long, ChunkState> states = borderWithNaturalTarget();
		long safe = key(1, 1);
		states.put(safe, ChunkState.SAFE);

		Infection.run(Infection.mapGrid(states), Set.of(key(0, 0)), new ScriptedRandom(0.0));

		assertEquals(ChunkState.SAFE, states.get(safe));
	}

	@Test
	void hasCheckSkipsOverlapOnLaterSample() {
		Map<Long, ChunkState> states = new HashMap<>();
		long first = key(0, 0);
		long second = key(6, 0);
		states.put(first, ChunkState.BORDER);
		states.put(second, ChunkState.BORDER);
		states.put(key(9, 0), ChunkState.DEMON);
		long overlap = key(3, 0);
		long onlySecond = key(7, 0);
		states.put(overlap, ChunkState.NATURAL);
		states.put(onlySecond, ChunkState.NATURAL);
		states.put(key(3, 1), ChunkState.SAFE);

		Infection.InfectionResult result = Infection.run(
				Infection.mapGrid(states),
				Set.of(first, second),
				new ScriptedRandom(0.0)
		);

		assertTrue(result.checkedKeys().contains(overlap));
		assertEquals(ChunkState.HOSTILE_BORDER, states.get(overlap));
		assertEquals(ChunkState.HOSTILE_BORDER, states.get(onlySecond));
	}

	@Test
	void paintingHostileBorderDemotesCardinalOccupiedToBorder() {
		Map<Long, ChunkState> states = borderWithNaturalTarget();
		long painted = key(1, 0);
		long occupied = key(1, 1);
		states.put(occupied, ChunkState.OCCUPIED);
		states.put(key(1, -1), ChunkState.SAFE);

		Infection.InfectionResult result = Infection.run(
				Infection.mapGrid(states),
				Set.of(key(0, 0)),
				new ScriptedRandom(0.0)
		);

		assertEquals(ChunkState.HOSTILE_BORDER, states.get(painted));
		assertEquals(ChunkState.BORDER, states.get(occupied));
		assertEquals(ChunkState.BORDER, result.changed().get(occupied));
		assertEquals(ChunkState.SAFE, states.get(key(1, -1)));
	}

	@Test
	void adjacencyPromotesSelfAndNeighborHostileBorder() {
		Map<Long, ChunkState> states = new HashMap<>();
		long center = key(0, 0);
		states.put(center, ChunkState.HOSTILE_BORDER);
		states.put(key(-1, 0), ChunkState.HOSTILE_BORDER);
		states.put(key(1, 0), ChunkState.HOSTILE_BORDER);
		states.put(key(0, -1), ChunkState.DEMON);
		states.put(key(0, 1), ChunkState.HOSTILE);

		states.put(key(2, 0), ChunkState.HOSTILE);
		states.put(key(1, -1), ChunkState.HOSTILE_BORDER);
		states.put(key(1, 1), ChunkState.DEMON);

		Map<Long, ChunkState> changed = new HashMap<>();
		Infection.applyAdjacency(Infection.mapGrid(states), center, changed);

		assertEquals(ChunkState.HOSTILE, states.get(center));
		assertEquals(ChunkState.HOSTILE, states.get(key(1, 0)));
		assertEquals(ChunkState.HOSTILE, changed.get(center));
		assertEquals(ChunkState.HOSTILE, changed.get(key(1, 0)));
	}

	@Test
	void shouldSkipAndProbabilityHelpers() {
		assertTrue(Infection.shouldSkipSample(36, false));
		assertFalse(Infection.shouldSkipSample(36, true));
		assertFalse(Infection.shouldSkipSample(35, false));
		assertEquals(Infection.BASE_PROBABILITY, Infection.convertProbability(false, 0));
		assertEquals(Infection.STRONGHOLD_PROBABILITY, Infection.convertProbability(true, 0));
		assertEquals(Infection.STRONGHOLD_PROBABILITY, Infection.convertProbability(false, 11));
	}

	private static Map<Long, ChunkState> borderWithNaturalTarget() {
		Map<Long, ChunkState> states = new HashMap<>();
		states.put(key(0, 0), ChunkState.BORDER);
		states.put(key(1, 0), ChunkState.NATURAL);
		return states;
	}

	private static long key(int x, int z) {
		return ChunkPos.asLong(x, z);
	}

	private static Random rolls(double... values) {
		return new ScriptedRandom(values);
	}

	private static final class ScriptedRandom extends Random {
		private final double[] values;
		private int index;

		private ScriptedRandom(double... values) {
			this.values = values;
		}

		@Override
		public double nextDouble() {
			if (values.length == 0) {
				return 0.0;
			}
			if (index >= values.length) {
				return values[values.length - 1];
			}
			return values[index++];
		}
	}
}
