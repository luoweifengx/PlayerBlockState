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
 * 每日日出时异步并行重算标脏区块的分数与状态，算完后回写主线程并清零停留分。
 */
public final class TerritoryDailyProcessor {
	private static final ExecutorService EXECUTOR = Executors.newWorkStealingPool(
			Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

	private TerritoryDailyProcessor() {
	}

	public static void trySchedule(ServerLevel level, OrganizationProvider orgProvider, SafeBiomeChecker safeChecker, long currentDay) {
		WorldRegionData data = WorldRegionData.get(level);
		Set<Long> recomputeKeys = data.resolveRecomputeChunkKeys();

		// PlayerBlockStatus.LOGGER.info(
		// 		"[pbs daily] trySchedule called for {} on day {} (dirtyChunks={}, activeChunks={}, recomputeChunks={})",
		// 		level.dimension().location(),
		// 		currentDay,
		// 		data.getDirtyChunkKeys().size(),
		// 		data.getActiveChunkKeyCount(),
		// 		recomputeKeys.size()
		// );

		if (recomputeKeys.isEmpty()) {
			// PlayerBlockStatus.LOGGER.info(
			// 		"[pbs daily] nothing to recompute for {} on day {}, skipping without advancing lastDailyDay",
			// 		level.dimension().location(),
			// 		currentDay
			// );
			return;
		}

		if (data.getDirtyChunkKeys().isEmpty() && !recomputeKeys.isEmpty()) {
			PlayerBlockStatus.LOGGER.warn(
					"[pbs daily] dirtyChunkKeys empty for {} on day {}, falling back to activeChunkKeys ({})",
					level.dimension().location(),
					currentDay,
					recomputeKeys.size()
			);
		}

		if (!data.tryBeginDailyRefresh(currentDay)) {
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily] trySchedule aborted for {} on day {} (tryBeginDailyRefresh returned false)",
					level.dimension().location(),
					currentDay
			);
			return;
		}

		Set<Long> dirtyKeys = new HashSet<>(recomputeKeys);

		PlayerBlockStatus.LOGGER.info(
				"[pbs daily] scheduling async recompute for {} on day {} (dirtyChunks={})",
				level.dimension().location(),
				currentDay,
				dirtyKeys.size()
		);

		Map<Long, ChunkState> neighborContext = buildNeighborContext(data, dirtyKeys);
		List<DailyChunkSnapshot> snapshots = captureSnapshots(level, data, safeChecker, dirtyKeys);
		PlayerBlockStatus.LOGGER.info(
				"[pbs daily] captured {} snapshots for {} (requested dirty={})",
				snapshots.size(),
				level.dimension().location(),
				dirtyKeys.size()
		);

		MinecraftServer server = level.getServer();

		EXECUTOR.submit(() -> {
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily] async compute started for {} on worker thread",
					level.dimension().location()
			);
			DailyComputeResult result = computeInParallel(snapshots, orgProvider, neighborContext);
			server.execute(() -> applyResult(level, data, result, dirtyKeys));
		});
	}

	private static List<DailyChunkSnapshot> captureSnapshots(
			ServerLevel level,
			WorldRegionData data,
			SafeBiomeChecker safeChecker,
			Set<Long> dirtyKeys
	) {
		List<DailyChunkSnapshot> snapshots = new ArrayList<>(dirtyKeys.size());

		for (long chunkKey : dirtyKeys) {
			ChunkTerritoryData chunk = data.getChunkForRecompute(chunkKey);
			if (chunk == null) {
				PlayerBlockStatus.LOGGER.warn(
						"[pbs daily] snapshot skipped for {}: no territory attachment found",
						new ChunkPos(chunkKey)
				);
				continue;
			}

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

	private static Map<Long, ChunkState> buildNeighborContext(WorldRegionData data, Set<Long> dirtyKeys) {
		Map<Long, ChunkState> context = new HashMap<>();
		for (long dirtyKey : dirtyKeys) {
			ChunkPos pos = new ChunkPos(dirtyKey);
			for (ChunkPos neighbor : getNeighbors(pos)) {
				long neighborKey = neighbor.toLong();
				if (dirtyKeys.contains(neighborKey) || context.containsKey(neighborKey)) {
					continue;
				}
				ChunkTerritoryData chunk = data.getChunk(neighborKey);
				context.put(neighborKey, chunk == null ? ChunkState.NATURAL : chunk.getState());
			}
		}
		return context;
	}

	private static DailyComputeResult computeInParallel(
			List<DailyChunkSnapshot> snapshots,
			OrganizationProvider orgProvider,
			Map<Long, ChunkState> neighborContext
	) {
		List<DailyChunkSnapshot> computedSnapshots = snapshots.parallelStream()
				.map(snapshot -> {
					ChunkPos chunkPos = new ChunkPos(snapshot.chunkKey());
					Map<UUID, Integer> cachedScores = ChunkScoreEngine.computeTotalScores(
							snapshot.placedBlocks(),
							snapshot.stayScores(),
							snapshot.scoreModifiers(),
							orgProvider
					);
					PlayerBlockStatus.LOGGER.info(
							"[pbs daily][{}] score compute: placedBlocks={}, stayScores={}, modifiers={}, blockScorePerBlock={}, totals={}",
							chunkPos,
							snapshot.placedBlocks().size(),
							snapshot.stayScores(),
							snapshot.scoreModifiers(),
							TerritoryConfig.blockScorePerBlock,
							cachedScores
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
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily][{}] base state: {} -> {}, org={}, threshold={}",
					new ChunkPos(snapshot.chunkKey()),
					snapshot.previousState(),
					base.state(),
					base.occupyingOrg(),
					TerritoryConfig.occupationThreshold
			);
		}

		Map<Long, ChunkState> finalStates = ChunkStateMachine.deriveBorderStatesFromBaseStates(baseStates, neighborContext);

		for (DailyChunkSnapshot snapshot : computedSnapshots) {
			ChunkState state = finalStates.get(snapshot.chunkKey());
			if (state == ChunkState.DEATH && allScoresPositive(snapshot.cachedScores())) {
				finalStates.put(snapshot.chunkKey(), ChunkState.NATURAL);
				occupyingOrgs.put(snapshot.chunkKey(), null);
			}
		}

		return new DailyComputeResult(finalStates, occupyingOrgs, snapshotByKey);
	}

	private static void applyResult(
			ServerLevel level,
			WorldRegionData data,
			DailyComputeResult result,
			Set<Long> dirtyKeys
	) {
		try {
			for (Map.Entry<Long, ChunkState> entry : result.finalStates().entrySet()) {
				long chunkKey = entry.getKey();
				ChunkState finalState = entry.getValue();
				DailyChunkSnapshot snapshot = result.snapshots().get(chunkKey);
				ChunkTerritoryData chunk = data.getChunk(chunkKey);

				if (snapshot != null) {
					if (chunk == null) {
						chunk = data.getOrCreateChunk(chunkKey);
					}
					chunk.getScoreModifiers().clear();
					chunk.getScoreModifiers().putAll(snapshot.scoreModifiers());
					chunk.getCachedScores().clear();
					chunk.getCachedScores().putAll(snapshot.cachedScores());
					chunk.setState(finalState);
					chunk.setOccupyingOrg(result.occupyingOrgs().get(chunkKey));
					chunk.clearStayScores();
					chunk.clearDirty();
					PlayerBlockStatus.LOGGER.info(
							"[pbs daily][{}] applied: state={}, org={}, cachedScores={}",
							new ChunkPos(chunkKey),
							finalState,
							result.occupyingOrgs().get(chunkKey),
							snapshot.cachedScores()
					);
					continue;
				}

				if (finalState == ChunkState.HOSTILE_BORDER) {
					chunk = data.getOrCreateChunk(chunkKey);
					chunk.setState(ChunkState.HOSTILE_BORDER);
					chunk.setOccupyingOrg(null);
					chunk.getCachedScores().clear();
					chunk.clearStayScores();
					chunk.clearDirty();
					continue;
				}

				if (chunk != null && !chunk.hasTerritoryData()) {
					data.removeEmptyChunk(chunkKey);
				}
			}

			data.getEntityChunkIndex().rebuildOccupiedFrom(data.getAllChunks());
			data.getDirtyChunkKeys().removeAll(dirtyKeys);
			data.finishDailyRefresh();

			PlayerBlockStatus.LOGGER.info(
					"[pbs daily] completed for {} (day={}, dirtyChunks={}, stateUpdates={}, lastDailyDay={})",
					level.dimension().location(),
					data.getLastDailyDay(),
					dirtyKeys.size(),
					result.finalStates().size(),
					data.getLastDailyDay()
			);
		} catch (Exception exception) {
			data.cancelDailyRefreshInProgress();
			PlayerBlockStatus.LOGGER.error("Daily territory refresh failed for {}", level.dimension().location(), exception);
		}
	}

	private static ChunkPos[] getNeighbors(ChunkPos pos) {
		return new ChunkPos[] {
				new ChunkPos(pos.x - 1, pos.z),
				new ChunkPos(pos.x + 1, pos.z),
				new ChunkPos(pos.x, pos.z - 1),
				new ChunkPos(pos.x, pos.z + 1)
		};
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
