package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

class DemonChunkSpreadTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void doesNotEnterSpreadWhenRollAtOrAboveProbability() {
		ChunkPos origin = new ChunkPos(3, 4);
		assertNull(DemonChunkSpread.pickSpreadTarget(origin, 0.01, 0.01, 0));
		assertNull(DemonChunkSpread.pickSpreadTarget(origin, 0.01, 0.5, 0));
	}

	@Test
	void entersSpreadThenPicksExactlyOneCardinalNeighbor() {
		ChunkPos origin = new ChunkPos(3, 4);
		ChunkPos[] expected = DemonChunkSpread.cardinalNeighbors(origin);
		assertEquals(4, expected.length);

		for (int i = 0; i < 4; i++) {
			assertEquals(expected[i], DemonChunkSpread.pickSpreadTarget(origin, 0.01, 0.0, i));
		}
	}

	@Test
	void spreadOnceNoOpWhenNeighborAlreadyDemon() {
		long origin = ChunkPos.asLong(0, 0);
		Set<Long> demons = new HashSet<>();
		demons.add(origin);
		for (ChunkPos neighbor : DemonChunkSpread.cardinalNeighbors(new ChunkPos(origin))) {
			demons.add(neighbor.toLong());
		}
		Set<Long> origins = Set.of(origin);

		List<Long> created = DemonChunkSpread.spreadOnce(
				origins,
				demons::contains,
				demons::add,
				0.01,
				true,
				new ScriptedRandom(0.0, 0)
		);

		assertTrue(created.isEmpty());
		assertEquals(5, demons.size());
	}

	@Test
	void spreadOnceConvertsChosenNeighborWhenNotDemon() {
		Set<Long> demons = new HashSet<>();
		demons.add(ChunkPos.asLong(0, 0));
		List<Long> converted = new ArrayList<>();

		List<Long> created = DemonChunkSpread.spreadOnce(
				demons,
				demons::contains,
				key -> {
					demons.add(key);
					converted.add(key);
				},
				0.01,
				true,
				new ScriptedRandom(0.0, 1)
		);

		long expected = new ChunkPos(1, 0).toLong();
		assertEquals(List.of(expected), created);
		assertEquals(List.of(expected), converted);
		assertTrue(demons.contains(expected));
	}

	@Test
	void spreadOnceSkippedWhenDisabledOrProbabilityZero() {
		Set<Long> demons = new HashSet<>();
		demons.add(ChunkPos.asLong(0, 0));

		assertTrue(DemonChunkSpread.spreadOnce(
				demons, demons::contains, demons::add, 0.01, false, new ScriptedRandom(0.0, 0)
		).isEmpty());
		assertTrue(DemonChunkSpread.spreadOnce(
				demons, demons::contains, demons::add, 0.0, true, new ScriptedRandom(0.0, 0)
		).isEmpty());
		assertEquals(1, demons.size());
	}

	@Test
	void eachOriginRollsOnceNotFourIndependentNeighbors() {
		Set<Long> demons = new HashSet<>();
		demons.add(ChunkPos.asLong(5, 5));
		int[] nextIntCalls = {0};
		Random random = new Random() {
			@Override
			public double nextDouble() {
				return 0.0;
			}

			@Override
			public int nextInt(int bound) {
				nextIntCalls[0]++;
				return 0;
			}
		};

		DemonChunkSpread.spreadOnce(demons, demons::contains, demons::add, 0.01, true, random);

		assertEquals(1, nextIntCalls[0]);
		assertTrue(demons.contains(new ChunkPos(4, 5).toLong()));
		assertFalse(demons.contains(new ChunkPos(6, 5).toLong()));
		assertFalse(demons.contains(new ChunkPos(5, 4).toLong()));
		assertFalse(demons.contains(new ChunkPos(5, 6).toLong()));
	}

	private static final class ScriptedRandom extends Random {
		private final double nextDouble;
		private final int nextInt;

		private ScriptedRandom(double nextDouble, int nextInt) {
			this.nextDouble = nextDouble;
			this.nextInt = nextInt;
		}

		@Override
		public double nextDouble() {
			return nextDouble;
		}

		@Override
		public int nextInt(int bound) {
			return nextInt;
		}
	}
}
