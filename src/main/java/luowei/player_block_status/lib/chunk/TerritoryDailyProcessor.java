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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.advancement.TerritoryAdvancements;
import luowei.player_block_status.lib.api.OrganizationProvider;
import luowei.player_block_status.lib.api.SafeBiomeChecker;
import luowei.player_block_status.lib.org.OrganizationRecord;
import luowei.player_block_status.lib.org.OrganizationService;

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
			Set<UUID> newlyOwnedOrgs = new HashSet<>();

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
					UUID previousOrg = chunk.getOccupyingOrg();
					chunk.getScoreModifiers().clear();
					chunk.getScoreModifiers().putAll(snapshot.scoreModifiers());
					chunk.getCachedScores().clear();
					chunk.getCachedScores().putAll(snapshot.cachedScores());
					if (previousState == ChunkState.DEMON && finalState != ChunkState.DEMON) {
						finalState = ChunkState.DEMON;
						occupyingOrg = previousOrg;
					}
					if (isNewlyOwned(previousState, previousOrg, finalState, occupyingOrg)) {
						newlyOwnedOrgs.add(occupyingOrg);
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
							|| chunk.getState() == ChunkState.DEMON
							|| chunk.getState() == ChunkState.HOSTILE)) {
						continue;
					}
					chunk = data.getOrCreateChunk(chunkKey);
					if (chunk.getState() == ChunkState.OCCUPIED
							|| chunk.getState() == ChunkState.BORDER
							|| chunk.getState() == ChunkState.DEMON
							|| chunk.getState() == ChunkState.HOSTILE) {
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

			Set<Long> neighborsBecameBorder = correctNeighborhoodAfterStateChanges(data, index, stateChangedKeys);
			Set<Long> borderHostileSeeds = new HashSet<>(stateChangedKeys);
			borderHostileSeeds.addAll(neighborsBecameBorder);
			correctHostilePlayerBorderCardinals(data, borderHostileSeeds);

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
			notifyNewlyOwnedMembers(level.getServer(), newlyOwnedOrgs);
			TerritoryAdvancements.checkHomeForOnlinePlayers(level.getServer());
		} catch (Exception exception) {
			data.cancelDailyRefreshInProgress();
			PlayerBlockStatus.LOGGER.error("Daily territory refresh failed for {}", level.dimension().location(), exception);
		}
	}

	/**
	 * 日更 apply 后只同步变化格的四邻（看四邻自己的四邻），不再向外洪水。
	 * BORDER ↔ OCCUPIED 与占领内部规则相同：四邻都是自己的占领族则内部，否则边界。
	 *
	 * @return 本次被改成 BORDER 的邻区（供后续敌对/玩家边界四邻降级）
	 */
	private static Set<Long> correctNeighborhoodAfterStateChanges(
			WorldRegionData data,
			EntityChunkIndex index,
			Set<Long> seedChangedKeys
	) {
		Set<Long> becameBorder = new HashSet<>();
		if (seedChangedKeys.isEmpty()) {
			return becameBorder;
		}

		ChunkStateMachine.NeighborResolver resolver = chunkKey -> {
			ChunkTerritoryData neighbor = data.getChunk(chunkKey);
			return neighbor == null
					? null
					: new ChunkStateMachine.NeighborChunkView(neighbor.getState(), neighbor.getOccupyingOrg());
		};

		Set<Long> neighbors = ChunkStateMachine.cardinalNeighborKeys(seedChangedKeys);
		for (long chunkKey : neighbors) {
			ChunkTerritoryData chunk = data.getChunk(chunkKey);
			if (chunk == null) {
				continue;
			}
			ChunkState state = chunk.getState();
			UUID org = chunk.getOccupyingOrg();
			ChunkState next = ChunkStateMachine.occupiedFamilyFromCardinals(resolver, chunkKey, org, state);
			if (next == state) {
				continue;
			}
			chunk.setState(next);
			data.persistChunkChange(chunkKey, chunk);
			index.replaceChunk(chunkKey, next, org);
			if (next == ChunkState.BORDER) {
				becameBorder.add(chunkKey);
			}
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily][{}] neighbor sync: {} -> {} (cardinal occupied-family)",
					formatChunk(new ChunkPos(chunkKey)),
					state,
					next
			);
		}
		return becameBorder;
	}

	/**
	 * 刚变成 BORDER / HOSTILE_BORDER 的格子，只对其四邻做一层降级（不洪水）。
	 */
	private static void correctHostilePlayerBorderCardinals(
			WorldRegionData data,
			Set<Long> seedKeys
	) {
		if (seedKeys.isEmpty()) {
			return;
		}

		ChunkStateMachine.NeighborResolver resolver = chunkKey -> {
			ChunkTerritoryData neighbor = data.getChunk(chunkKey);
			return neighbor == null
					? null
					: new ChunkStateMachine.NeighborChunkView(neighbor.getState(), neighbor.getOccupyingOrg());
		};

		Map<Long, ChunkState> demotes = ChunkStateMachine.cardinalDemotesFromNewBorders(resolver, seedKeys);
		if (demotes.isEmpty()) {
			return;
		}
		for (Map.Entry<Long, ChunkState> entry : demotes.entrySet()) {
			long chunkKey = entry.getKey();
			ChunkTerritoryData chunk = data.getChunk(chunkKey);
			ChunkState previous = chunk == null ? ChunkState.NATURAL : chunk.getState();
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily][{}] neighbor sync: {} -> {} (cardinal border/hostile-border)",
					formatChunk(new ChunkPos(chunkKey)),
					previous,
					entry.getValue()
			);
		}
		data.applyCardinalBorderDemotes(demotes);
	}

	/**
	 * 新状态为某账户占领族（OCCUPIED/BORDER），且原先并非同一账户的占领族。
	 * 邻域修正（BORDER→OCCUPIED）不算新纳入。
	 */
	static boolean isNewlyOwned(ChunkState previousState, UUID previousOrg, ChunkState newState, UUID newOrg) {
		if (newOrg == null || newState == null || !newState.isOccupiedFamily()) {
			return false;
		}
		return previousState == null
				|| !previousState.isOccupiedFamily()
				|| !newOrg.equals(previousOrg);
	}

	private static void notifyNewlyOwnedMembers(MinecraftServer server, Set<UUID> newlyOwnedOrgs) {
		if (server == null || newlyOwnedOrgs.isEmpty()) {
			return;
		}
		for (UUID orgId : newlyOwnedOrgs) {
			Collection<UUID> memberIds = OrganizationService.getOrganization(server, orgId)
					.map(OrganizationRecord::members)
					.orElseGet(() -> Set.of(orgId));
			for (UUID memberId : memberIds) {
				ServerPlayer player = server.getPlayerList().getPlayer(memberId);
				if (player != null) {
					player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6f, 1.2f);
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
