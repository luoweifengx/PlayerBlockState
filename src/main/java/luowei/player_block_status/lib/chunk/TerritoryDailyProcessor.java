package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import luowei.player_block_status.lib.advancement.TerritoryAdvancements;
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

	public enum ScheduleResult {
		SCHEDULED,
		NOTHING_TO_RECOMPUTE,
		REFRESH_ALREADY_IN_PROGRESS
	}

	public record ScheduleAttempt(ScheduleResult result, int chunkCount) {
		public boolean scheduled() {
			return result == ScheduleResult.SCHEDULED;
		}
	}

	public static void trySchedule(ServerLevel level, OrganizationProvider orgProvider, SafeBiomeChecker safeChecker, long currentDay) {
		WorldRegionData data = WorldRegionData.get(level);
		Set<Long> recomputeKeys = data.resolveRecomputeChunkKeys();

		if (recomputeKeys.isEmpty()) {
			if (data.acknowledgeIdleDaily(currentDay)) {
				PlayerBlockStatus.LOGGER.debug(
						"[pbs daily] no dirty chunks for {} on day {}, skipping recompute",
						level.dimension().location(),
						currentDay
				);
				DemonChunks.spreadForDay(level);
			}
			return;
		}

		if (!data.tryBeginDailyRefresh(currentDay)) {
			PlayerBlockStatus.LOGGER.debug(
					"[pbs daily] trySchedule aborted for {} on day {} (tryBeginDailyRefresh returned false)",
					level.dimension().location(),
					currentDay
			);
			return;
		}

		scheduleRefresh(level, orgProvider, safeChecker, data, currentDay, recomputeKeys);
	}

	public static ScheduleAttempt tryScheduleForced(
			ServerLevel level,
			OrganizationProvider orgProvider,
			SafeBiomeChecker safeChecker,
			long currentDay
	) {
		WorldRegionData data = WorldRegionData.get(level);
		Set<Long> recomputeKeys = data.resolveForceRecomputeChunkKeys();

		if (recomputeKeys.isEmpty()) {
			return new ScheduleAttempt(ScheduleResult.NOTHING_TO_RECOMPUTE, 0);
		}

		if (!data.tryBeginDailyRefreshForce(currentDay)) {
			return new ScheduleAttempt(ScheduleResult.REFRESH_ALREADY_IN_PROGRESS, 0);
		}

		scheduleRefresh(level, orgProvider, safeChecker, data, currentDay, recomputeKeys);
		return new ScheduleAttempt(ScheduleResult.SCHEDULED, recomputeKeys.size());
	}

	private static void scheduleRefresh(
			ServerLevel level,
			OrganizationProvider orgProvider,
			SafeBiomeChecker safeChecker,
			WorldRegionData data,
			long currentDay,
			Set<Long> recomputeKeys
	) {
		Set<Long> dirtyKeys = new HashSet<>(recomputeKeys);
		Map<Long, Integer> epochSnapshot = data.snapshotDirtyEpochs(dirtyKeys);

		PlayerBlockStatus.LOGGER.info(
				"[pbs daily] scheduling async recompute for {} on day {} (dirtyChunks={}): {}",
				level.dimension().location(),
				currentDay,
				dirtyKeys.size(),
				formatChunkKeys(dirtyKeys)
		);

		Map<Long, ChunkStateMachine.NeighborChunkView> neighborContext = buildNeighborContext(data, dirtyKeys);
		List<DailyChunkSnapshot> snapshots = captureSnapshots(level, data, safeChecker, dirtyKeys);
		PlayerBlockStatus.LOGGER.debug(
				"[pbs daily] captured {} snapshots for {} (requested dirty={})",
				snapshots.size(),
				level.dimension().location(),
				dirtyKeys.size()
		);

		MinecraftServer server = level.getServer();

		EXECUTOR.submit(() -> {
			PlayerBlockStatus.LOGGER.debug(
					"[pbs daily] async compute started for {} on worker thread",
					level.dimension().location()
			);
			DailyComputeResult result = computeInParallel(snapshots, orgProvider, neighborContext);
			server.execute(() -> applyResult(level, data, result, epochSnapshot, currentDay));
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

	/**
	 * 收集 dirty 四周（含敌对边界延伸半径）的真实邻区状态与归属，避免工作 map 缺 key 被当成 NATURAL。
	 */
	private static Map<Long, ChunkStateMachine.NeighborChunkView> buildNeighborContext(
			WorldRegionData data,
			Set<Long> dirtyKeys
	) {
		Map<Long, ChunkStateMachine.NeighborChunkView> context = new HashMap<>();
		int extension = TerritoryConfig.hostileBorderExtensionChunks;
		// 至少收集四邻，供 isOccupiedEdge；并覆盖敌对边界延伸半径
		int radius = Math.max(1, extension);
		for (long dirtyKey : dirtyKeys) {
			ChunkPos pos = new ChunkPos(dirtyKey);
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					if (Math.max(Math.abs(dx), Math.abs(dz)) > radius) {
						continue;
					}
					long neighborKey = ChunkPos.asLong(pos.x + dx, pos.z + dz);
					if (dirtyKeys.contains(neighborKey) || context.containsKey(neighborKey)) {
						continue;
					}
					ChunkTerritoryData chunk = data.getChunk(neighborKey);
					context.put(
							neighborKey,
							chunk == null
									? ChunkStateMachine.NeighborChunkView.natural()
									: new ChunkStateMachine.NeighborChunkView(chunk.getState(), chunk.getOccupyingOrg())
					);
				}
			}
		}
		return context;
	}

	private static DailyComputeResult computeInParallel(
			List<DailyChunkSnapshot> snapshots,
			OrganizationProvider orgProvider,
			Map<Long, ChunkStateMachine.NeighborChunkView> neighborContext
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
							"[pbs daily][{}] score compute: placedBlocks={} stayScores={} modifiers={} blockScorePerBlock={} totals={}",
							formatChunk(chunkPos),
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
			if (snapshot.previousState() == ChunkState.DEMON) {
				baseStates.put(snapshot.chunkKey(), ChunkState.DEMON);
				occupyingOrgs.put(snapshot.chunkKey(), snapshot.occupyingOrg());
				continue;
			}
			BaseStateResult base = ChunkStateMachine.computeBaseStateFromSnapshot(snapshot);
			baseStates.put(snapshot.chunkKey(), base.state());
			occupyingOrgs.put(snapshot.chunkKey(), base.occupyingOrg());
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily][{}] base state: {} -> {} org={} threshold={}",
					formatChunk(new ChunkPos(snapshot.chunkKey())),
					snapshot.previousState(),
					base.state(),
					base.occupyingOrg(),
					TerritoryConfig.occupationThreshold
			);
		}

		Map<Long, ChunkState> finalStates = ChunkStateMachine.deriveBorderStatesFromBaseStates(
				baseStates,
				occupyingOrgs,
				neighborContext
		);

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
			Map<Long, Integer> epochSnapshot,
			long currentDay
	) {
		try {
			EntityChunkIndex index = data.getEntityChunkIndex();
			Set<Long> stateChangedKeys = new HashSet<>();

			for (Map.Entry<Long, ChunkState> entry : result.finalStates().entrySet()) {
				long chunkKey = entry.getKey();
				ChunkState finalState = entry.getValue();
				DailyChunkSnapshot snapshot = result.snapshots().get(chunkKey);
				ChunkTerritoryData chunk = data.getChunk(chunkKey);
				UUID occupyingOrg = result.occupyingOrgs().get(chunkKey);

				if (snapshot != null) {
					if (chunk == null) {
						chunk = data.getOrCreateChunk(chunkKey);
					}
					ChunkState previousState = chunk.getState();
					chunk.getScoreModifiers().clear();
					chunk.getScoreModifiers().putAll(snapshot.scoreModifiers());
					chunk.getCachedScores().clear();
					chunk.getCachedScores().putAll(snapshot.cachedScores());
					if (previousState == ChunkState.DEMON && finalState != ChunkState.DEMON) {
						finalState = ChunkState.DEMON;
						occupyingOrg = chunk.getOccupyingOrg();
					}
					chunk.setState(finalState);
					// OCCUPIED 与 BORDER 均保留基础态归属；其它状态按计算结果写入（可为 null）
					chunk.setOccupyingOrg(occupyingOrg);
					chunk.clearStayScores();
					data.persistChunkChange(chunkKey, chunk);
					index.replaceChunk(chunkKey, finalState, occupyingOrg);
					if (previousState != finalState) {
						stateChangedKeys.add(chunkKey);
					}
					PlayerBlockStatus.LOGGER.info(
							"[pbs daily][{}] applied: {} -> {} changed={} org={} placedBlocks={} stay={} modifiers={} scores={}",
							formatChunk(new ChunkPos(chunkKey)),
							previousState,
							finalState,
							previousState != finalState,
							occupyingOrg,
							snapshot.placedBlocks().size(),
							snapshot.stayScores(),
							snapshot.scoreModifiers(),
							snapshot.cachedScores()
					);
					continue;
				}

				if (finalState == ChunkState.HOSTILE_BORDER) {
					// 无 snapshot 的 HOSTILE：写前再确认当前不是 OCCUPIED/BORDER/DEMON
					if (chunk != null
							&& (chunk.getState() == ChunkState.OCCUPIED
							|| chunk.getState() == ChunkState.BORDER
							|| chunk.getState() == ChunkState.DEMON)) {
						continue;
					}
					chunk = data.getOrCreateChunk(chunkKey);
					if (chunk.getState() == ChunkState.OCCUPIED
							|| chunk.getState() == ChunkState.BORDER
							|| chunk.getState() == ChunkState.DEMON) {
						continue;
					}
					ChunkState previousState = chunk.getState();
					chunk.setState(ChunkState.HOSTILE_BORDER);
					chunk.setOccupyingOrg(null);
					chunk.getCachedScores().clear();
					chunk.clearStayScores();
					data.persistChunkChange(chunkKey, chunk);
					index.replaceChunk(chunkKey, ChunkState.HOSTILE_BORDER, null);
					if (previousState != ChunkState.HOSTILE_BORDER) {
						stateChangedKeys.add(chunkKey);
					}
					PlayerBlockStatus.LOGGER.info(
							"[pbs daily][{}] applied derived HOSTILE_BORDER: {} -> HOSTILE_BORDER changed={}",
							formatChunk(new ChunkPos(chunkKey)),
							previousState,
							previousState != ChunkState.HOSTILE_BORDER
					);
					continue;
				}

				if (chunk != null && !chunk.hasTerritoryData()) {
					data.removeEmptyChunk(chunkKey);
				}
			}

			correctNeighborhoodAfterStateChanges(data, index, stateChangedKeys);

			data.clearRecomputedDirtyKeys(epochSnapshot);
			data.finishDailyRefresh(currentDay);
			DemonChunks.spreadForDay(level);

			PlayerBlockStatus.LOGGER.info(
					"[pbs daily] completed for {} (day={}, dirtyChunks={}, recomputed={}, stateChanged={}, lastDailyDay={})",
					level.dimension().location(),
					currentDay,
					epochSnapshot.size(),
					result.snapshots().size(),
					stateChangedKeys.size(),
					data.getLastDailyDay()
			);
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily] recomputed chunks: {}",
					formatChunkKeys(result.snapshots().keySet())
			);
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily] state changed chunks: {}",
					formatChunkKeys(stateChangedKeys)
			);
			TerritoryAdvancements.checkHomeForOnlinePlayers(level.getServer());
		} catch (Exception exception) {
			data.cancelDailyRefreshInProgress();
			PlayerBlockStatus.LOGGER.error("Daily territory refresh failed for {}", level.dimension().location(), exception);
		}
	}

	/**
	 * 日更 apply 后，对状态确有变化的区块做邻域即时修正（切比雪夫 ≤ {@link TerritoryConfig#hostileBorderExtensionChunks}）。
	 * <ul>
	 *   <li>自身为 NATURAL：邻域内存在 BORDER → HOSTILE_BORDER</li>
	 *   <li>自身为 BORDER：四邻均为同 org 占领族 → OCCUPIED</li>
	 * </ul>
	 * 修正后对距离 2 内邻区再扫最多 1 遍，避免无限循环。
	 */
	private static void correctNeighborhoodAfterStateChanges(
			WorldRegionData data,
			EntityChunkIndex index,
			Set<Long> seedChangedKeys
	) {
		if (seedChangedKeys.isEmpty()) {
			return;
		}

		int extension = TerritoryConfig.hostileBorderExtensionChunks;
		ChunkStateMachine.NeighborResolver resolver = chunkKey -> {
			ChunkTerritoryData neighbor = data.getChunk(chunkKey);
			return neighbor == null
					? null
					: new ChunkStateMachine.NeighborChunkView(neighbor.getState(), neighbor.getOccupyingOrg());
		};

		Set<Long> frontier = new HashSet<>(seedChangedKeys);
		for (int pass = 0; pass < 2 && !frontier.isEmpty(); pass++) {
			Set<Long> corrected = new HashSet<>();
			for (long chunkKey : frontier) {
				ChunkTerritoryData chunk = data.getChunk(chunkKey);
				if (chunk == null) {
					continue;
				}

				ChunkState state = chunk.getState();
				if (state == ChunkState.DEMON) {
					continue;
				}
				if (state == ChunkState.NATURAL) {
					if (!ChunkStateMachine.hasBorderWithinChebyshev(resolver, chunkKey, extension)) {
						continue;
					}
					chunk.setState(ChunkState.HOSTILE_BORDER);
					chunk.setOccupyingOrg(null);
					chunk.getCachedScores().clear();
					data.persistChunkChange(chunkKey, chunk);
					index.replaceChunk(chunkKey, ChunkState.HOSTILE_BORDER, null);
					corrected.add(chunkKey);
					seedChangedKeys.add(chunkKey);
					PlayerBlockStatus.LOGGER.info(
							"[pbs daily][{}] neighborhood fix: NATURAL -> HOSTILE_BORDER (border within chebyshev {})",
							formatChunk(new ChunkPos(chunkKey)),
							extension
					);
				} else if (state == ChunkState.BORDER) {
					UUID org = chunk.getOccupyingOrg();
					if (!ChunkStateMachine.allCardinalNeighborsOwnOccupiedFamily(resolver, chunkKey, org)) {
						continue;
					}
					chunk.setState(ChunkState.OCCUPIED);
					data.persistChunkChange(chunkKey, chunk);
					index.replaceChunk(chunkKey, ChunkState.OCCUPIED, org);
					corrected.add(chunkKey);
					seedChangedKeys.add(chunkKey);
					PlayerBlockStatus.LOGGER.info(
							"[pbs daily][{}] neighborhood fix: BORDER -> OCCUPIED (all cardinal own occupied family)",
							formatChunk(new ChunkPos(chunkKey))
					);
				}
			}

			if (corrected.isEmpty() || pass == 1) {
				break;
			}

			frontier = new HashSet<>();
			for (long key : corrected) {
				ChunkPos pos = new ChunkPos(key);
				for (int dx = -extension; dx <= extension; dx++) {
					for (int dz = -extension; dz <= extension; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) > extension) {
							continue;
						}
						frontier.add(ChunkPos.asLong(pos.x + dx, pos.z + dz));
					}
				}
			}
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

	private static String formatChunk(ChunkPos pos) {
		return "[" + pos.x + ", " + pos.z + "]";
	}

	private static String formatChunkKeys(Collection<Long> keys) {
		if (keys == null || keys.isEmpty()) {
			return "(none)";
		}
		return keys.stream()
				.map(ChunkPos::new)
				.sorted(Comparator.comparingInt((ChunkPos pos) -> pos.x).thenComparingInt(pos -> pos.z))
				.map(TerritoryDailyProcessor::formatChunk)
				.collect(Collectors.joining(", "));
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
