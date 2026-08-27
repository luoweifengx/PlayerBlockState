package luowei.player_block_status.lib.chunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.debug.TerritoryPerf;

/**
 * 结构链式认领：玩家放置后，对六邻中 {@link TerritoryConfig#STRUCTURE_BLOCK_SENTINEL} 方块做分 tick BFS，
 * 改写为同一归属 UUID；空气/无记录/已有主人则停止传播。
 */
public final class StructureClaimProcessor {
	private static final BlockPos[] NEIGHBOR_OFFSETS = {
			new BlockPos(1, 0, 0),
			new BlockPos(-1, 0, 0),
			new BlockPos(0, 1, 0),
			new BlockPos(0, -1, 0),
			new BlockPos(0, 0, 1),
			new BlockPos(0, 0, -1)
	};

	private static final Map<ResourceKey<Level>, Deque<ClaimJob>> JOBS_BY_DIMENSION = new HashMap<>();

	private StructureClaimProcessor() {
	}

	/**
	 * 从玩家刚放置的方块出发，对其六邻启动 sentinel 链式传播。
	 */
	public static void enqueue(ServerLevel level, BlockPos seed, UUID owner) {
		if (owner == null || TerritoryConfig.isStructureSentinel(owner)) {
			return;
		}

		ClaimJob job = new ClaimJob(owner, seed);
		JOBS_BY_DIMENSION.computeIfAbsent(level.dimension(), key -> new ArrayDeque<>()).addLast(job);
		PlayerBlockStatus.LOGGER.debug(
				"[pbs claim] started owner={} seed={} neighbors={}",
				owner,
				seed,
				job.queue
		);
	}

	/** @deprecated 使用 {@link #enqueue(ServerLevel, BlockPos, UUID)}；bounds 已不再参与传播条件。 */
	@Deprecated
	public static void enqueue(
			ServerLevel level,
			WorldRegionData data,
			StructureBounds bounds,
			BlockPos seed,
			UUID owner
	) {
		enqueue(level, seed, owner);
	}

	public static void tick(ServerLevel level) {
		ProfilerFiller profiler = Profiler.get();
		profiler.push(TerritoryPerf.STRUCTURE_CLAIM);
		long t0 = System.nanoTime();
		int claimed = 0;
		int jobsRemaining = 0;
		try {
			Deque<ClaimJob> jobs = JOBS_BY_DIMENSION.get(level.dimension());
			if (jobs == null || jobs.isEmpty()) {
				return;
			}

			WorldRegionData data = WorldRegionData.get(level);
			int budget = TerritoryConfig.structureClaimBlocksPerTick;

			while (budget > 0 && !jobs.isEmpty()) {
				ClaimJob job = jobs.peekFirst();
				if (job == null) {
					jobs.pollFirst();
					continue;
				}

				BlockPos pos = job.queue.pollFirst();
				if (pos == null) {
					jobs.pollFirst();
					PlayerBlockStatus.LOGGER.debug(
							"[pbs claim] completed owner={} claimed={} seed={}",
							job.owner,
							job.claimedCount,
							job.seed
					);
					continue;
				}

				budget--;
				if (!job.claimSentinel(data, pos)) {
					continue;
				}

				claimed++;
				for (BlockPos offset : NEIGHBOR_OFFSETS) {
					BlockPos neighbor = pos.offset(offset);
					if (job.visited.add(neighbor.asLong())) {
						job.queue.addLast(neighbor);
					}
				}
			}
			jobsRemaining = jobs.size();
		} finally {
			profiler.pop();
		}
		TerritoryPerf.logStructureClaim(claimed, jobsRemaining, System.nanoTime() - t0);
	}

	private static final class ClaimJob {
		private final UUID owner;
		private final BlockPos seed;
		private final Set<Long> visited = new HashSet<>();
		private final Deque<BlockPos> queue = new ArrayDeque<>();
		private int claimedCount;
		private boolean seedLogged;

		private ClaimJob(UUID owner, BlockPos seed) {
			this.owner = owner;
			this.seed = seed.immutable();
			visited.add(this.seed.asLong());
			for (BlockPos offset : NEIGHBOR_OFFSETS) {
				BlockPos neighbor = this.seed.offset(offset);
				if (visited.add(neighbor.asLong())) {
					queue.addLast(neighbor);
				}
			}
		}

		private boolean claimSentinel(WorldRegionData data, BlockPos pos) {
			if (!seedLogged) {
				UUID seedOwner = data.getPlacedBlockOwner(seed);
				PlayerBlockStatus.LOGGER.debug(
						"[pbs claim] seed-owner seed={} owner={} sentinel={}",
						seed,
						seedOwner,
						TerritoryConfig.isStructureSentinel(seedOwner)
				);
				seedLogged = true;
			}

			UUID current = data.getPlacedBlockOwner(pos);
			boolean sentinel = TerritoryConfig.isStructureSentinel(current);
			if (claimedCount == 0) {
				PlayerBlockStatus.LOGGER.debug(
						"[pbs claim] probe pos={} owner={} sentinel={}",
						pos,
						current,
						sentinel
				);
			}
			if (!sentinel) {
				return false;
			}

			data.claimStructureBlock(pos, owner);
			claimedCount++;
			PlayerBlockStatus.LOGGER.debug("[pbs claim] claimed pos={} count={}", pos, claimedCount);
			return true;
		}
	}
}
