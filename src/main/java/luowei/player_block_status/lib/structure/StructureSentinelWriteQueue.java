package luowei.player_block_status.lib.structure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.chunk.TerritoryConfig;
import luowei.player_block_status.lib.chunk.WorldRegionData;
import luowei.player_block_status.lib.debug.TerritoryPerf;

/**
 * 结构生成 Worker 捕获的 sentinel 坐标：入队后仅在 Server 线程分批写入 attachment。
 * <p>
 * 禁止在世界生成 Worker 上调用 {@code level.getChunk} / {@link luowei.player_block_status.lib.chunk.ChunkTerritoryAccess}
 * / {@link WorldRegionData#markStructureSentinel}，否则会与主线程死锁。
 * <p>
 * <b>落盘风险</b>：队列仅存内存。正常运行靠维度 tick 按预算刷空；关服时
 * {@link #flushAll(MinecraftServer)} 会尽量在主线程一次刷完。若进程被强杀或关服前仍有未刷入项，
 * 对应 sentinel 会丢失（实例去重键也可能未写入 SavedData）。未做 SavedData 持久化 pending。
 */
public final class StructureSentinelWriteQueue {
	private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentLinkedQueue<PendingJob>> BY_DIMENSION =
			new ConcurrentHashMap<>();

	private StructureSentinelWriteQueue() {
	}

	/**
	 * Worker 安全：仅拷贝坐标入队，不触碰 chunk / SavedData。
	 */
	public static void enqueue(
			ServerLevel level,
			ResourceKey<Structure> structureKey,
			long instanceKey,
			ChunkPos originChunk,
			Collection<BlockPos> positions
	) {
		Deque<BlockPos> remaining = new ArrayDeque<>(positions.size());
		for (BlockPos pos : positions) {
			remaining.addLast(pos.immutable());
		}
		PendingJob job = new PendingJob(structureKey, instanceKey, originChunk, remaining);
		BY_DIMENSION.computeIfAbsent(level.dimension(), key -> new ConcurrentLinkedQueue<>()).offer(job);
	}

	/** 维度 tick：按 {@link TerritoryConfig#structureClaimBlocksPerTick} 预算刷写。 */
	public static void tick(ServerLevel level) {
		// 1.21.5：Level.getProfiler 已移除，Spark / F3 看的是线程局部 Profiler.get()。
		ProfilerFiller profiler = Profiler.get();
		profiler.push(TerritoryPerf.SENTINEL_DRAIN);
		long t0 = System.nanoTime();
		DrainStats stats;
		try {
			stats = drain(level, TerritoryConfig.structureClaimBlocksPerTick);
		} finally {
			profiler.pop();
		}
		TerritoryPerf.logSentinelDrain(stats.drained, stats.queueRemaining, stats.chunksTouched, System.nanoTime() - t0);
	}

	/** 关服等：无预算上限，尽量刷空该维度队列。不走 10ms warn。 */
	public static void flushAll(ServerLevel level) {
		ProfilerFiller profiler = Profiler.get();
		profiler.push(TerritoryPerf.SENTINEL_FLUSH_ALL);
		try {
			drain(level, Integer.MAX_VALUE);
		} finally {
			profiler.pop();
		}
	}

	public static void flushAll(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			flushAll(level);
		}
	}

	private static DrainStats drain(ServerLevel level, int budget) {
		ConcurrentLinkedQueue<PendingJob> jobs = BY_DIMENSION.get(level.dimension());
		if (jobs == null || jobs.isEmpty() || budget <= 0) {
			return DrainStats.IDLE;
		}

		WorldRegionData data = WorldRegionData.get(level);
		int remainingBudget = budget;
		int drained = 0;
		Set<Long> chunksTouched = new HashSet<>();

		while (remainingBudget > 0) {
			PendingJob job = jobs.peek();
			if (job == null) {
				break;
			}

			if (!job.registrationDone) {
				job.firstRegistration = data.tryMarkStructureInstanceRegistered(job.instanceKey);
				job.registrationDone = true;
			}

			BlockPos pos = job.remaining.pollFirst();
			if (pos == null) {
				jobs.poll();
				if (job.firstRegistration || job.marked > 0) {
					PlayerBlockStatus.LOGGER.debug(
							"Structure {} placed at {}: marked {} template blocks as sentinel (firstRegistration={})",
							job.structureKey.location(),
							job.originChunk,
							job.marked,
							job.firstRegistration
					);
				}
				continue;
			}

			data.markStructureSentinel(pos);
			job.marked++;
			remainingBudget--;
			drained++;
			chunksTouched.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
		}

		return new DrainStats(drained, countQueueRemaining(jobs), chunksTouched.size());
	}

	/** drain 结束后该维度队列里剩余格子总数。 */
	private static int countQueueRemaining(ConcurrentLinkedQueue<PendingJob> jobs) {
		List<Deque<BlockPos>> remaining = new ArrayList<>();
		for (PendingJob job : jobs) {
			remaining.add(job.remaining);
		}
		return TerritoryPerf.sumRemainingBlocks(remaining);
	}

	private record DrainStats(int drained, int queueRemaining, int chunksTouched) {
		private static final DrainStats IDLE = new DrainStats(0, 0, 0);
	}

	private static final class PendingJob {
		private final ResourceKey<Structure> structureKey;
		private final long instanceKey;
		private final ChunkPos originChunk;
		private final Deque<BlockPos> remaining;
		private boolean registrationDone;
		private boolean firstRegistration;
		private int marked;

		private PendingJob(
				ResourceKey<Structure> structureKey,
				long instanceKey,
				ChunkPos originChunk,
				Deque<BlockPos> remaining
		) {
			this.structureKey = structureKey;
			this.instanceKey = instanceKey;
			this.originChunk = originChunk;
			this.remaining = remaining;
		}
	}
}
