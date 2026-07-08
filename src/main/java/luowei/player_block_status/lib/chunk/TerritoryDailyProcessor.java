package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.api.OrganizationProvider;
import luowei.player_block_status.lib.api.SafeBiomeChecker;

/**
 * 每日日出时异步并行重算领土分数与状态，算完后回写主线程并清零停留分。
 */
public final class TerritoryDailyProcessor {
	private static final ExecutorService EXECUTOR = Executors.newWorkStealingPool(
			Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

	private TerritoryDailyProcessor() {
	}

	public static void trySchedule(ServerLevel level, OrganizationProvider orgProvider, SafeBiomeChecker safeChecker, long currentDay) {
		WorldRegionData data = WorldRegionData.get(level);
		if (!data.tryBeginDailyRefresh(currentDay)) {
			return;
		}

		List<DailyChunkSnapshot> snapshots = captureSnapshots(level, data, safeChecker);
		MinecraftServer server = level.getServer();

		EXECUTOR.submit(() -> {
			DailyComputeResult result = computeInParallel(snapshots, orgProvider);
			server.execute(() -> applyResult(level, data, result));
		});
	}

	private static List<DailyChunkSnapshot> captureSnapshots(ServerLevel level, WorldRegionData data, SafeBiomeChecker safeChecker) {
		List<DailyChunkSnapshot> snapshots = new ArrayList<>(data.getAllChunks().size());

		for (Map.Entry<Long, ChunkTerritoryData> entry : data.getAllChunks().entrySet()) {
			long chunkKey = entry.getKey();
			ChunkTerritoryData chunk = entry.getValue();
			ChunkPos chunkPos = new ChunkPos(chunkKey);

			Map<UUID, Integer> modifiers = new HashMap<>(chunk.getScoreModifiers());
			ChunkStateMachine.applyDeathRecoveryToModifiers(chunk.getState(), modifiers);

			boolean safeChunk = safeChecker.isSafeChunk(level, chunkPos.getBlockAt(0, level.getMinY(), 0));
			snapshots.add(new DailyChunkSnapshot(
					chunkKey,
					new HashMap<>(chunk.getPlacedBlocks()),
					new HashMap<>(chunk.getStayScores()),
					modifiers,
					chunk.getState(),
					chunk.getOccupyingOrg(),
					safeChunk,
					Map.of()
			));
		}

		return snapshots;
	}

	private static DailyComputeResult computeInParallel(List<DailyChunkSnapshot> snapshots, OrganizationProvider orgProvider) {
		List<DailyChunkSnapshot> computedSnapshots = snapshots.parallelStream()
				.map(snapshot -> {
					Map<UUID, Integer> cachedScores = ChunkScoreEngine.computeTotalScores(
							snapshot.placedBlocks(),
							snapshot.stayScores(),
							snapshot.scoreModifiers(),
							orgProvider
					);
					return snapshot.withCachedScores(cachedScores);
				})
				.toList();

		Map<Long, DailyChunkSnapshot> snapshotByKey = computedSnapshots.stream()
				.collect(Collectors.toMap(DailyChunkSnapshot::chunkKey, snapshot -> snapshot));

		Map<Long, ChunkState> baseStates = new HashMap<>();
		Map<Long, UUID> occupyingOrgs = new HashMap<>();

		for (DailyChunkSnapshot snapshot : computedSnapshots) {
			BaseStateResult base = ChunkStateMachine.computeBaseStateFromSnapshot(snapshot);
			baseStates.put(snapshot.chunkKey(), base.state());
			occupyingOrgs.put(snapshot.chunkKey(), base.occupyingOrg());
		}

		Map<Long, ChunkState> finalStates = ChunkStateMachine.deriveBorderStatesFromBaseStates(baseStates);

		for (DailyChunkSnapshot snapshot : computedSnapshots) {
			ChunkState state = finalStates.get(snapshot.chunkKey());
			if (state == ChunkState.DEATH && allScoresPositive(snapshot.cachedScores())) {
				finalStates.put(snapshot.chunkKey(), ChunkState.NATURAL);
				occupyingOrgs.put(snapshot.chunkKey(), null);
			}
		}

		return new DailyComputeResult(finalStates, occupyingOrgs, snapshotByKey);
	}

	private static void applyResult(ServerLevel level, WorldRegionData data, DailyComputeResult result) {
		try {
			for (Map.Entry<Long, ChunkState> entry : result.finalStates().entrySet()) {
				long chunkKey = entry.getKey();
				ChunkTerritoryData chunk = data.getChunk(chunkKey);
				DailyChunkSnapshot snapshot = result.snapshots().get(chunkKey);
				if (chunk == null || snapshot == null) {
					continue;
				}

				chunk.getScoreModifiers().clear();
				chunk.getScoreModifiers().putAll(snapshot.scoreModifiers());
				chunk.getCachedScores().clear();
				chunk.getCachedScores().putAll(snapshot.cachedScores());
				chunk.setState(entry.getValue());
				chunk.setOccupyingOrg(result.occupyingOrgs().get(chunkKey));
				chunk.clearStayScores();
				chunk.clearDirty();
			}

			data.getEntityChunkIndex().rebuildOccupiedFrom(data.getAllChunks());
			data.getDirtyChunkKeys().clear();
			data.finishDailyRefresh();
			data.setDirty();

			PlayerBlockStatus.LOGGER.info(
					"Daily territory refresh completed for {} ({} chunks)",
					level.dimension().location(),
					result.finalStates().size()
			);
		} catch (Exception exception) {
			data.cancelDailyRefreshInProgress();
			PlayerBlockStatus.LOGGER.error("Daily territory refresh failed for {}", level.dimension().location(), exception);
		}
	}

	private static boolean allScoresPositive(Map<UUID, Integer> scores) {
		for (int score : scores.values()) {
			if (score <= 0) {
				return false;
			}
		}
		return true;
	}

	record DailyChunkSnapshot(
			long chunkKey,
			Map<Long, UUID> placedBlocks,
			Map<UUID, Integer> stayScores,
			Map<UUID, Integer> scoreModifiers,
			ChunkState previousState,
			UUID occupyingOrg,
			boolean safeChunk,
			Map<UUID, Integer> cachedScores
	) {
		DailyChunkSnapshot withCachedScores(Map<UUID, Integer> scores) {
			return new DailyChunkSnapshot(
					chunkKey,
					placedBlocks,
					stayScores,
					scoreModifiers,
					previousState,
					occupyingOrg,
					safeChunk,
					scores
			);
		}
	}

	record BaseStateResult(ChunkState state, UUID occupyingOrg) {
	}

	record DailyComputeResult(
			Map<Long, ChunkState> finalStates,
			Map<Long, UUID> occupyingOrgs,
			Map<Long, DailyChunkSnapshot> snapshots
	) {
	}
}
