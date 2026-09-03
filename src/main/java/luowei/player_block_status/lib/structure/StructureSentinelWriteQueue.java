package luowei.player_block_status.lib.structure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.chunk.TerritoryConfig;
import luowei.player_block_status.lib.chunk.WorldRegionData;
import luowei.player_block_status.lib.debug.TerritoryPerf;

/**
 * 结构生成 Worker 捕获的 sentinel 坐标：入队后仅在 Server 线程写入 attachment。
 * <p>
 * 禁止在世界生成 Worker 上调用 {@code level.getChunk} / {@link luowei.player_block_status.lib.chunk.ChunkTerritoryAccess}
 * / {@link WorldRegionData#markStructureSentinel}，否则会与主线程死锁。
 * <p>
 * 写入时序：按 {@link ChunkPos} 分桶；{@code CHUNK_LOAD} 时区块已是完整 {@link LevelChunk}，
 * 直接写 attachment，不调用阻塞 {@code getChunk}。tick 只处理已经在内存里的区块（{@code getChunkNow}）。
 * 关服 {@link #flushAll(MinecraftServer)} 才允许阻塞加载以尽量落盘。
 * <p>
 * <b>落盘风险</b>：队列仅存内存。若进程被强杀，未刷入的 sentinel 会丢失。未做 SavedData 持久化 pending。
 */
public final class StructureSentinelWriteQueue {
	private static final ConcurrentHashMap<ResourceKey<Level>, DimensionPending> BY_DIMENSION =
			new ConcurrentHashMap<>();

	private StructureSentinelWriteQueue() {
	}

	/**
	 * Worker 安全：仅按区块分桶拷贝坐标，不触碰 chunk / SavedData。
	 */
	public static void enqueue(
			ServerLevel level,
			ResourceKey<Structure> structureKey,
			long instanceKey,
			ChunkPos originChunk,
			Collection<BlockPos> positions
	) {
		DimensionPending pending = BY_DIMENSION.computeIfAbsent(level.dimension(), key -> new DimensionPending());
		for (BlockPos pos : positions) {
			BlockPos immutable = pos.immutable();
			long chunkKey = ChunkPos.asLong(immutable.getX() >> 4, immutable.getZ() >> 4);
			pending.byChunk.computeIfAbsent(chunkKey, key -> new ConcurrentLinkedQueue<>()).add(immutable);
		}
		pending.registrations.offer(new PendingRegistration(structureKey, instanceKey, originChunk, positions.size()));
	}

	/**
	 * 区块已加载为完整 {@link LevelChunk}：刷写该区块队列，不 {@code getChunk}。
	 */
	public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		DimensionPending pending = BY_DIMENSION.get(level.dimension());
		if (pending == null) {
			return;
		}
		long chunkKey = chunk.getPos().toLong();
		if (!pending.byChunk.containsKey(chunkKey)) {
			return;
		}

		ProfilerFiller profiler = Profiler.get();
		profiler.push(TerritoryPerf.SENTINEL_DRAIN);
		long t0 = System.nanoTime();
		DrainStats stats;
		try {
			WorldRegionData data = WorldRegionData.get(level);
			flushRegistrations(data, pending);
			stats = drainLoadedChunk(data, pending, chunk, Integer.MAX_VALUE, new TerritoryPerf.StageNanos());
		} finally {
			profiler.pop();
		}
		if (stats.drained == 0) {
			return;
		}
		TerritoryPerf.logSentinelDrain(
				stats.drained,
				countQueueRemaining(pending),
				stats.chunksTouched,
				stats.newChunks,
				stats.hotWrites,
				System.nanoTime() - t0,
				stats.stages
		);
	}

	/** 维度 tick：只刷 {@code getChunkNow} 已在内存的区块，不打断生成流水线。 */
	public static void tick(ServerLevel level) {
		ProfilerFiller profiler = Profiler.get();
		profiler.push(TerritoryPerf.SENTINEL_DRAIN);
		long t0 = System.nanoTime();
		DrainStats stats;
		try {
			stats = drainLoadedOnly(level, TerritoryConfig.structureClaimBlocksPerTick);
		} finally {
			profiler.pop();
		}
		TerritoryPerf.logSentinelDrain(
				stats.drained,
				stats.queueRemaining,
				stats.chunksTouched,
				stats.newChunks,
				stats.hotWrites,
				System.nanoTime() - t0,
				stats.stages
		);
	}

	/** 关服等：无预算上限；未加载的区块才阻塞 {@code getChunk}。不走 10ms warn。 */
	public static void flushAll(ServerLevel level) {
		ProfilerFiller profiler = Profiler.get();
		profiler.push(TerritoryPerf.SENTINEL_FLUSH_ALL);
		try {
			drainFlush(level);
		} finally {
			profiler.pop();
		}
	}

	public static void flushAll(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			flushAll(level);
		}
	}

	private static DrainStats drainLoadedOnly(ServerLevel level, int budget) {
		DimensionPending pending = BY_DIMENSION.get(level.dimension());
		if (pending == null || budget <= 0) {
			return DrainStats.IDLE;
		}
		if (pending.byChunk.isEmpty() && pending.registrations.isEmpty()) {
			return DrainStats.IDLE;
		}

		WorldRegionData data = WorldRegionData.get(level);
		flushRegistrations(data, pending);
		if (pending.byChunk.isEmpty()) {
			return DrainStats.IDLE;
		}

		TerritoryPerf.StageNanos stages = new TerritoryPerf.StageNanos();
		int remainingBudget = budget;
		int drained = 0;
		int chunksTouched = 0;
		int newChunks = 0;
		int hotWrites = 0;
		List<Long> keys = new ArrayList<>(pending.byChunk.keySet());
		for (long chunkKey : keys) {
			if (remainingBudget <= 0) {
				break;
			}
			ChunkPos chunkPos = new ChunkPos(chunkKey);
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
			if (chunk == null) {
				continue;
			}
			DrainStats part = drainLoadedChunk(data, pending, chunk, remainingBudget, stages);
			if (part.drained == 0) {
				continue;
			}
			remainingBudget -= part.drained;
			drained += part.drained;
			chunksTouched += part.chunksTouched;
			newChunks += part.newChunks;
			hotWrites += part.hotWrites;
		}
		if (drained == 0) {
			return DrainStats.IDLE;
		}
		return new DrainStats(drained, countQueueRemaining(pending), chunksTouched, newChunks, hotWrites, stages);
	}

	private static void drainFlush(ServerLevel level) {
		DimensionPending pending = BY_DIMENSION.get(level.dimension());
		if (pending == null) {
			return;
		}
		WorldRegionData data = WorldRegionData.get(level);
		flushRegistrations(data, pending);
		List<Long> keys = new ArrayList<>(pending.byChunk.keySet());
		for (long chunkKey : keys) {
			ChunkPos chunkPos = new ChunkPos(chunkKey);
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
			if (chunk == null) {
				chunk = level.getChunk(chunkPos.x, chunkPos.z);
			}
			drainLoadedChunk(data, pending, chunk, Integer.MAX_VALUE, null);
		}
	}

	private static DrainStats drainLoadedChunk(
			WorldRegionData data,
			DimensionPending pending,
			LevelChunk chunk,
			int budget,
			TerritoryPerf.StageNanos stages
	) {
		if (budget <= 0) {
			return DrainStats.IDLE;
		}
		long chunkKey = chunk.getPos().toLong();
		ConcurrentLinkedQueue<BlockPos> queue = pending.byChunk.get(chunkKey);
		if (queue == null || queue.isEmpty()) {
			pending.byChunk.remove(chunkKey, queue);
			return DrainStats.IDLE;
		}

		TerritoryPerf.StageNanos split = stages != null ? stages : new TerritoryPerf.StageNanos();
		int drained = 0;
		int remainingBudget = budget;
		BlockPos pos;
		while (remainingBudget > 0 && (pos = queue.poll()) != null) {
			if (ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4) != chunkKey) {
				long otherKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
				pending.byChunk.computeIfAbsent(otherKey, key -> new ConcurrentLinkedQueue<>()).add(pos);
				continue;
			}
			data.markStructureSentinel(chunk, pos, split);
			drained++;
			remainingBudget--;
		}
		if (queue.isEmpty()) {
			pending.byChunk.remove(chunkKey, queue);
		}
		if (drained == 0) {
			return DrainStats.IDLE;
		}
		int hotWrites = Math.max(0, drained - 1);
		int newChunks = drained > 0 ? 1 : 0;
		return new DrainStats(drained, 0, 1, newChunks, hotWrites, split);
	}

	private static void flushRegistrations(WorldRegionData data, DimensionPending pending) {
		PendingRegistration registration;
		while ((registration = pending.registrations.poll()) != null) {
			boolean first = data.tryMarkStructureInstanceRegistered(registration.instanceKey);
			if (first || registration.captured > 0) {
				PlayerBlockStatus.LOGGER.debug(
						"Structure {} placed at {}: enqueued {} template blocks as sentinel (firstRegistration={})",
						registration.structureKey.location(),
						registration.originChunk,
						registration.captured,
						first
				);
			}
		}
	}

	private static int countQueueRemaining(DimensionPending pending) {
		int sum = 0;
		for (ConcurrentLinkedQueue<BlockPos> queue : pending.byChunk.values()) {
			if (queue != null) {
				sum += queue.size();
			}
		}
		return sum;
	}

	private record DrainStats(
			int drained,
			int queueRemaining,
			int chunksTouched,
			int newChunks,
			int hotWrites,
			TerritoryPerf.StageNanos stages
	) {
		private static final DrainStats IDLE = new DrainStats(0, 0, 0, 0, 0, TerritoryPerf.StageNanos.ZERO);
	}

	private static final class DimensionPending {
		private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<BlockPos>> byChunk = new ConcurrentHashMap<>();
		private final ConcurrentLinkedQueue<PendingRegistration> registrations = new ConcurrentLinkedQueue<>();
	}

	private record PendingRegistration(
			ResourceKey<Structure> structureKey,
			long instanceKey,
			ChunkPos originChunk,
			int captured
	) {
	}
}
