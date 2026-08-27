package luowei.player_block_status.lib.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

class TerritoryPerfTest {
	@Test
	void sumRemainingBlocksAddsEachJobQueueSize() {
		List<Collection<String>> jobs = List.of(
				new ArrayDeque<>(List.of("a", "b", "c")),
				new ArrayDeque<>(List.of("d")),
				new ArrayDeque<>()
		);
		assertEquals(4, TerritoryPerf.sumRemainingBlocks(jobs));
	}

	@Test
	void sumRemainingBlocksTreatsNullQueueAsZero() {
		List<Collection<String>> jobs = new ArrayList<>();
		jobs.add(new ArrayDeque<>(List.of("only")));
		jobs.add(null);
		assertEquals(1, TerritoryPerf.sumRemainingBlocks(jobs));
	}

	@Test
	void warnBudgetStartsAtTenMilliseconds() {
		assertFalse(TerritoryPerf.exceedsWarnBudget(9_999_999L));
		assertTrue(TerritoryPerf.exceedsWarnBudget(10_000_000L));
		assertTrue(TerritoryPerf.exceedsWarnBudget(10_000_001L));
	}
}
