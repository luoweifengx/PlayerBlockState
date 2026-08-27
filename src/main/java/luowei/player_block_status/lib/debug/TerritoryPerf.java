package luowei.player_block_status.lib.debug;

import java.util.Collection;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * 结构 sentinel 刷写 / 链式认领的 tick 级性能埋点。
 * <p>
 * Profiler 分段名给 Spark / F3 饼图检索；墙钟告警只打限预算路径，关服 flush 不要用 10ms warn。
 */
public final class TerritoryPerf {
	/** Spark / F3：sentinel 队列按预算 drain 整段 */
	public static final String SENTINEL_DRAIN = "pbs.sentinelDrain";
	/** Spark / F3：关服无上限 flush 整段（不走 10ms warn） */
	public static final String SENTINEL_FLUSH_ALL = "pbs.sentinelFlushAll";
	/** Spark / F3：结构链式认领整段 */
	public static final String STRUCTURE_CLAIM = "pbs.structureClaim";

	/** 限预算 tick 墙钟超过此值打 warn（10ms） */
	public static final long DRAIN_WARN_NANOS = 10_000_000L;

	private TerritoryPerf() {
	}

	/**
	 * 各 job 剩余格子数求和。{@code queues} 中的 null 计为 0。
	 */
	public static int sumRemainingBlocks(Iterable<? extends Collection<?>> queues) {
		int sum = 0;
		for (Collection<?> queue : queues) {
			if (queue != null) {
				sum += queue.size();
			}
		}
		return sum;
	}

	public static boolean exceedsWarnBudget(long nanos) {
		return nanos >= DRAIN_WARN_NANOS;
	}

	public static void logSentinelDrain(int drained, int queueRemaining, int chunksTouched, long drainNs) {
		if (drained == 0 && queueRemaining == 0) {
			return;
		}
		if (exceedsWarnBudget(drainNs)) {
			PlayerBlockStatus.LOGGER.warn(
					"[pbs perf] sentinelDrain drained={} queueRemaining={} chunksTouched={} drainNs={}",
					drained,
					queueRemaining,
					chunksTouched,
					drainNs
			);
			return;
		}
		PlayerBlockStatus.LOGGER.debug(
				"[pbs perf] sentinelDrain drained={} queueRemaining={} chunksTouched={} drainNs={}",
				drained,
				queueRemaining,
				chunksTouched,
				drainNs
		);
	}

	public static void logStructureClaim(int claimed, int jobsRemaining, long claimNs) {
		if (claimed == 0 && jobsRemaining == 0) {
			return;
		}
		if (exceedsWarnBudget(claimNs)) {
			PlayerBlockStatus.LOGGER.warn(
					"[pbs perf] structureClaim claimed={} jobsRemaining={} claimNs={}",
					claimed,
					jobsRemaining,
					claimNs
			);
			return;
		}
		PlayerBlockStatus.LOGGER.debug(
				"[pbs perf] structureClaim claimed={} jobsRemaining={} claimNs={}",
				claimed,
				jobsRemaining,
				claimNs
		);
	}
}
