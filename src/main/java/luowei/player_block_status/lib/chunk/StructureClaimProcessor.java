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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * 结构认领：从玩家放置点出发，在包围盒内对连通非空气方块做 BFS，分 tick 写入归属。
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

	public static void enqueue(
			ServerLevel level,
			WorldRegionData data,
			StructureBounds bounds,
			BlockPos seed,
			UUID owner
	) {
		ClaimJob job = ClaimJob.from(bounds, seed, owner);
		JOBS_BY_DIMENSION.computeIfAbsent(level.dimension(), key -> new ArrayDeque<>()).addLast(job);
		PlayerBlockStatus.LOGGER.info(
				"Structure {} claim flood-fill started by {} from {}",
				bounds.id(),
				owner,
				seed
		);
	}

	public static void tick(ServerLevel level) {
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
				PlayerBlockStatus.LOGGER.info("Structure {} flood-fill completed ({} blocks)", job.structureId, job.claimedCount);
				continue;
			}

			budget--;
			if (!job.claimBlock(level, data, pos)) {
				continue;
			}

			for (BlockPos offset : NEIGHBOR_OFFSETS) {
				BlockPos neighbor = pos.offset(offset);
				if (job.visited.add(neighbor.asLong())) {
					job.queue.addLast(neighbor);
				}
			}
		}
	}

	private static final class ClaimJob {
		private final UUID structureId;
		private final UUID owner;
		private final IntBounds bounds;
		private final Set<Long> visited = new HashSet<>();
		private final Deque<BlockPos> queue = new ArrayDeque<>();
		private int claimedCount;

		private ClaimJob(UUID structureId, UUID owner, IntBounds bounds, BlockPos seed) {
			this.structureId = structureId;
			this.owner = owner;
			this.bounds = bounds;
			visited.add(seed.asLong());
			for (BlockPos offset : NEIGHBOR_OFFSETS) {
				BlockPos neighbor = seed.offset(offset);
				if (visited.add(neighbor.asLong())) {
					queue.addLast(neighbor);
				}
			}
		}

		private static ClaimJob from(StructureBounds bounds, BlockPos seed, UUID owner) {
			return new ClaimJob(bounds.id(), owner, IntBounds.from(bounds), seed);
		}

		private boolean claimBlock(ServerLevel level, WorldRegionData data, BlockPos pos) {
			if (!bounds.contains(pos)) {
				return false;
			}

			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				return false;
			}

			if (data.getPlacedBlockOwner(pos) != null) {
				return false;
			}

			data.claimStructureBlock(pos, owner);
			claimedCount++;
			return true;
		}
	}

	private record IntBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		private static IntBounds from(StructureBounds bounds) {
			return new IntBounds(
					Math.min(bounds.cornerA().getX(), bounds.cornerB().getX()),
					Math.min(bounds.cornerA().getY(), bounds.cornerB().getY()),
					Math.min(bounds.cornerA().getZ(), bounds.cornerB().getZ()),
					Math.max(bounds.cornerA().getX(), bounds.cornerB().getX()),
					Math.max(bounds.cornerA().getY(), bounds.cornerB().getY()),
					Math.max(bounds.cornerA().getZ(), bounds.cornerB().getZ())
			);
		}

		private boolean contains(BlockPos pos) {
			return pos.getX() >= minX && pos.getX() <= maxX
					&& pos.getY() >= minY && pos.getY() <= maxY
					&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
		}
	}
}
