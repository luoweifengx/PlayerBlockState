package luowei.player_block_status.lib.chunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.api.OrganizationProvider;
import luowei.player_block_status.lib.debug.MapExportTrace;
import luowei.player_block_status.lib.debug.TerritoryPerf;

/**
 * 维度领土协调器：区块数据存于 chunk Attachment，维度元数据存于 {@link DimensionTerritoryData}。
 */
public final class WorldRegionData {
	private final ServerLevel level;
	private final DimensionTerritoryData dimensionData;

	private WorldRegionData(ServerLevel level, DimensionTerritoryData dimensionData) {
		this.level = level;
		this.dimensionData = dimensionData;
	}

	public static WorldRegionData get(ServerLevel level) {
		return new WorldRegionData(level, DimensionTerritoryData.get(level));
	}

	public static WorldRegionData getForMapExport(ServerLevel level, MapExportTrace trace) {
		if (trace != null) {
			trace.step("DimensionTerritoryData.computeIfAbsent begin");
		}
		long loadStart = System.nanoTime();
		DimensionTerritoryData dimensionData = DimensionTerritoryData.get(level);
		long loadMs = (System.nanoTime() - loadStart) / 1_000_000L;
		PlayerBlockStatus.LOGGER.debug(
				"[pbs persist] DimensionTerritoryData getForMapExport in {}ms active={}",
				loadMs,
				dimensionData.getActiveChunkKeys().size()
		);
		if (trace != null) {
			trace.step(
					"DimensionTerritoryData loaded in %dms (activeChunkKeys=%d)",
					loadMs,
					dimensionData.getActiveChunkKeys().size()
			);
		}
		return new WorldRegionData(level, dimensionData);
	}

	public ChunkTerritoryData getOrCreateChunk(long chunkKey) {
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		ChunkTerritoryData chunk = ChunkTerritoryAccess.getOrCreate(level, chunkPos);
		if (chunk.hasTerritoryData()) {
			trackActiveChunk(chunkKey);
			PlayerBlockStatus.LOGGER.debug(
					"[pbs persist] getOrCreateChunk tracks because hasTerritoryData cx={} cz={} key={} state={} occupyingOrg={} placedBlocks={} activeSize={}",
					chunkPos.x,
					chunkPos.z,
					chunkKey,
					chunk.getState(),
					chunk.getOccupyingOrg(),
					chunk.getPlacedBlocks().size(),
					dimensionData.getActiveChunkKeys().size()
			);
		}
		return chunk;
	}

	public ChunkTerritoryData getChunk(long chunkKey) {
		ChunkTerritoryData chunk = ChunkTerritoryAccess.getIfPresent(level, new ChunkPos(chunkKey));
		return requireTerritoryOrLog(chunkKey, "getChunk", chunk);
	}

	/** 每日重算/全量收集：只要 attachment 有数据即返回，必要时触发 chunk 加载。 */
	public ChunkTerritoryData getChunkForRecompute(long chunkKey) {
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		level.getChunk(chunkPos.x, chunkPos.z);
		ChunkTerritoryData chunk = ChunkTerritoryAccess.getIfPresent(level, chunkPos);
		return requireTerritoryOrLog(chunkKey, "getChunkForRecompute", chunk);
	}

	/** 查询用：仅已加载区块；未加载或无领土数据返回 {@code null}。 */
	public ChunkTerritoryData getChunkIfLoaded(long chunkKey) {
		ChunkTerritoryData chunk = ChunkTerritoryAccess.getIfLoaded(level, new ChunkPos(chunkKey));
		if (chunk == null || !chunk.hasTerritoryData()) {
			return null;
		}
		return chunk;
	}

	public Map<Long, ChunkTerritoryData> getAllChunks() {
		return collectAllChunks();
	}

	Map<Long, ChunkTerritoryData> collectAllChunks() {
		Map<Long, ChunkTerritoryData> chunks = new HashMap<>();
		for (long chunkKey : new HashSet<>(dimensionData.getActiveChunkKeys())) {
			ChunkTerritoryData chunk = getChunkForRecompute(chunkKey);
			if (chunk != null) {
				chunks.put(chunkKey, chunk);
				continue;
			}
			/*
			 * 附件是真相；没附件即自然，倒排索引应删掉该 key。
			 * 读不到就保留索引是找补，会掩盖持久化问题、留下幽灵边界。
			 * 应先完善 chunk attachment / 维度 SavedData 落盘；持久化可靠后再考虑找补。
			 * 因此暂时恢复「读不到 / 空附件 → 当自然并清 occupied/border 索引」。
			 *
			 * 找补（暂禁用）：force-load 读不到 attachment 时不清 occupied/border 索引。
			 * untrackActiveChunk(chunkKey, false, "collectAllChunks miss");
			 */
			ChunkPos chunkPos = new ChunkPos(chunkKey);
			PlayerBlockStatus.LOGGER.debug(
					"[pbs persist] collectAllChunks miss force-load found no territory data cx={} cz={} key={}",
					chunkPos.x,
					chunkPos.z,
					chunkKey
			);
			untrackActiveChunk(chunkKey, true, "collectAllChunks miss");
		}
		return chunks;
	}

	public EntityChunkIndex getEntityChunkIndex() {
		return dimensionData.getEntityChunkIndex();
	}

	public Set<Long> getDemonChunkKeys() {
		return dimensionData.getDemonChunkKeys();
	}

	/**
	 * 感染抽样用的玩家 BORDER。当前直接返回倒排索引，不再从 attachment 回填。
	 * <p>
	 * 附件是真相；没附件即自然，倒排索引应删掉该 key。
	 * 读不到就保留索引 / 从 attachment 修补索引是找补，会掩盖持久化问题、留下幽灵边界。
	 * 应先完善 chunk attachment / 维度 SavedData 落盘；持久化可靠后再考虑找补。
	 * 因此暂时恢复「读不到 / 空附件 → 当自然并清 occupied/border 索引」，感染只读 {@code getAllBorderChunks()}。
	 */
	public Set<Long> resolveAllBorderChunks() {
		/*
		 * 找补（暂禁用）：索引为空时从 attachment 修补并打 warn。
		 * {@code untrackActiveChunk} 曾在 attachment 暂未读到时把 BORDER 从索引抹掉，导致 {@code sampled=0}。
		 *
		 * EntityChunkIndex index = getEntityChunkIndex();
		 * Set<Long> fromIndex = index.getAllBorderChunks();
		 * if (!fromIndex.isEmpty()) {
		 *     return fromIndex;
		 * }
		 * Set<Long> repaired = new HashSet<>();
		 * for (long chunkKey : getActiveChunkKeys()) {
		 *     ChunkTerritoryData chunk = getChunk(chunkKey);
		 *     if (chunk == null || chunk.getState() != ChunkState.BORDER || chunk.getOccupyingOrg() == null) {
		 *         continue;
		 *     }
		 *     index.replaceChunk(chunkKey, ChunkState.BORDER, chunk.getOccupyingOrg());
		 *     repaired.add(chunkKey);
		 * }
		 * if (!repaired.isEmpty()) {
		 *     PlayerBlockStatus.LOGGER.warn(
		 *             "[pbs infect] border index was empty; repaired {} BORDER chunk(s) from attachments",
		 *             repaired.size()
		 *     );
		 * } else {
		 *     PlayerBlockStatus.LOGGER.warn(
		 *             "[pbs infect] no BORDER chunks in index or attachments (active={})",
		 *             getActiveChunkKeyCount()
		 *     );
		 * }
		 * return repaired;
		 */
		return getEntityChunkIndex().getAllBorderChunks();
	}

	/**
	 * 直接改写区块状态（敌对边界 / 敌对占领等），不标脏、不走日更 write-back。
	 * 仍将 attachment 标为待存盘，并更新倒排索引。
	 * {@link ChunkState#HOSTILE} / {@link ChunkState#HOSTILE_BORDER} / {@link ChunkState#DEMON}
	 * 会清空 occupyingOrg；其它转换（含 OCCUPIED→BORDER）保留原 occupyingOrg。
	 */
	public void applyDirectState(long chunkKey, ChunkState state) {
		if (state == null) {
			return;
		}
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		boolean clearOrg = state == ChunkState.HOSTILE
				|| state == ChunkState.HOSTILE_BORDER
				|| state == ChunkState.DEMON;
		if (chunk.getState() == state && (!clearOrg || chunk.getOccupyingOrg() == null)) {
			syncDemonIndex(chunkKey, chunk);
			return;
		}
		if (!chunk.getState().canBeReplacedBy(state)) {
			return;
		}
		chunk.setState(state);
		if (clearOrg) {
			chunk.setOccupyingOrg(null);
		}
		persistChunkChange(chunkKey, chunk);
		getEntityChunkIndex().replaceChunk(chunkKey, state, chunk.getOccupyingOrg());
	}

	/**
	 * 四邻降级写入：OCCUPIED→BORDER 保留 occupyingOrg；HOSTILE→HOSTILE_BORDER 无归属。
	 */
	public void applyCardinalBorderDemotes(Map<Long, ChunkState> demotes) {
		if (demotes == null || demotes.isEmpty()) {
			return;
		}
		for (Map.Entry<Long, ChunkState> entry : demotes.entrySet()) {
			applyDirectState(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * 强制写成恶魔区块：覆盖任意现有类型，清空占领归属。
	 */
	public void convertToDemon(long chunkKey) {
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		if (chunk.getState() == ChunkState.DEMON) {
			syncDemonIndex(chunkKey, chunk);
			return;
		}
		chunk.setState(ChunkState.DEMON);
		chunk.setOccupyingOrg(null);
		persistChunkChange(chunkKey, chunk);
		getEntityChunkIndex().replaceChunk(chunkKey, ChunkState.DEMON, null);
	}

	/**
	 * 将本维度全部恶魔区块退回自然并标脏，供下次日更按分数重算。
	 */
	public int clearAllDemonChunks() {
		Set<Long> keys = new HashSet<>(dimensionData.getDemonChunkKeys());
		int cleared = 0;
		for (long chunkKey : keys) {
			ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
			if (chunk.getState() != ChunkState.DEMON) {
				dimensionData.removeDemonChunk(chunkKey);
				continue;
			}
			chunk.setState(ChunkState.NATURAL);
			chunk.setOccupyingOrg(null);
			persistChunkChange(chunkKey, chunk);
			getEntityChunkIndex().replaceChunk(chunkKey, ChunkState.NATURAL, null);
			markChunkDirty(chunkKey);
			cleared++;
		}
		return cleared;
	}

	public void markChunkDirty(long chunkKey) {
		if (dimensionData.markChunkDirty(chunkKey)) {
			PlayerBlockStatus.LOGGER.debug(
					"[pbs persist] chunk {} marked dirty (dirtyCount={}, activeCount={})",
					new ChunkPos(chunkKey),
					dimensionData.getDirtyChunkKeys().size(),
					dimensionData.getActiveChunkKeys().size()
			);
		}
	}

	public Set<Long> getDirtyChunkKeys() {
		return dimensionData.getDirtyChunkKeys();
	}

	public Map<Long, Integer> snapshotDirtyEpochs(Set<Long> keys) {
		return dimensionData.snapshotDirtyEpochs(keys);
	}

	public void clearRecomputedDirtyKeys(Map<Long, Integer> epochSnapshot) {
		dimensionData.clearRecomputedDirtyKeys(epochSnapshot);
	}

	public int getActiveChunkKeyCount() {
		return dimensionData.getActiveChunkKeys().size();
	}

	public Set<Long> getActiveChunkKeys() {
		return Set.copyOf(dimensionData.getActiveChunkKeys());
	}

	/**
	 * 本次每日重算只处理已落盘的 dirty 集合；空则跳过，不再回退到全部 active。
	 */
	public Set<Long> resolveRecomputeChunkKeys() {
		return new HashSet<>(dimensionData.getDirtyChunkKeys());
	}

	public boolean acknowledgeIdleDaily(long currentDay) {
		return dimensionData.acknowledgeIdleDaily(currentDay);
	}

	/**
	 * 调试强制刷新：只重算当前 dirty；dirty 空则返回空（NOTHING_TO_RECOMPUTE）。
	 */
	public Set<Long> resolveForceRecomputeChunkKeys() {
		Set<Long> keys = new HashSet<>(dimensionData.getDirtyChunkKeys());
		if (!keys.isEmpty()) {
			return keys;
		}
		// 不明确是否删除，暂时以注释形式保留。
		// dirty 空时不再回退到全部 active，避免强制 refresh 静默全量。
		// keys.addAll(dimensionData.getActiveChunkKeys());
		return keys;
	}

	public List<StructureBounds> getPendingStructures() {
		return dimensionData.getPendingStructures();
	}

	public void registerStructure(StructureBounds bounds) {
		dimensionData.registerStructure(bounds);
	}

	public boolean tryMarkStructureInstanceRegistered(long instanceKey) {
		return dimensionData.tryMarkStructureInstanceRegistered(instanceKey);
	}

	public long getLastDailyDay() {
		return dimensionData.getLastDailyDay();
	}

	/** 旧存档 {@code last_daily_day} 若仍是日历日，可能大于当前 tick 周期；压齐以便本周期能调度。 */
	public void alignLastRefreshPeriod(long currentPeriod) {
		dimensionData.alignLastRefreshPeriod(currentPeriod);
	}

	public boolean tryBeginDailyRefresh(long currentDay) {
		return dimensionData.tryBeginDailyRefresh(currentDay);
	}

	public boolean tryBeginDailyRefreshForce(long currentDay) {
		return dimensionData.tryBeginDailyRefreshForce(currentDay);
	}

	public void finishDailyRefresh(long currentDay) {
		dimensionData.finishDailyRefresh(currentDay);
	}

	public void cancelDailyRefreshInProgress() {
		dimensionData.cancelDailyRefreshInProgress();
	}

	public void onBlockPlaced(ServerLevel level, BlockPos pos, UUID playerId, OrganizationProvider orgProvider) {
		UUID scoreEntity = resolveScoreEntity(level, playerId, orgProvider);

		long chunkKey = new ChunkPos(pos).toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		chunk.addPlacedBlock(pos, scoreEntity);
		persistChunkChange(chunkKey, chunk);
		markChunkDirty(chunkKey);
		StructureClaimProcessor.enqueue(level, pos, scoreEntity);
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		PlayerBlockStatus.LOGGER.info(
				"[pbs] player placed block at ({}, {}, {}) chunk=[{}, {}] player={} scoreEntity={} placedCount={} dirtyCount={}",
				pos.getX(),
				pos.getY(),
				pos.getZ(),
				chunkPos.x,
				chunkPos.z,
				playerId,
				scoreEntity,
				chunk.getPlacedBlocks().size(),
				dimensionData.getDirtyChunkKeys().size()
		);
	}

	public UUID getPlacedBlockOwner(BlockPos pos) {
		ChunkTerritoryData chunk = ChunkTerritoryAccess.getIfPresent(level, new ChunkPos(pos));
		if (chunk == null) {
			return null;
		}
		return chunk.getPlacedBlockOwner(pos);
	}

	/**
	 * 结构模板方块：写入 sentinel 归属（不计分；不标脏，待玩家链式认领后再参与日更）。
	 * 仅应在 Server 线程调用；世界生成 Worker 请经 {@link luowei.player_block_status.lib.structure.StructureSentinelWriteQueue} 入队。
	 * <p>
	 * 已持有完整 {@link LevelChunk} 时走 {@link #markStructureSentinel(LevelChunk, BlockPos, TerritoryPerf.StageNanos)}，
	 * 避免 {@code getChunk} 打断生成流水线。
	 */
	public void markStructureSentinel(BlockPos pos) {
		markStructureSentinel(pos, null);
	}

	/**
	 * {@code stages} 非 null 时累加四段墙钟，供 tick 级 {@code [pbs perf]} 拆分。
	 * 会阻塞 {@code getChunk}，只给关服 flush 等必须落盘的路径。
	 */
	public void markStructureSentinel(BlockPos pos, TerritoryPerf.StageNanos stages) {
		long t0 = stages != null ? System.nanoTime() : 0L;
		UUID existing = getPlacedBlockOwner(pos);
		if (stages != null) {
			stages.lookupNs += System.nanoTime() - t0;
		}
		if (existing != null && !TerritoryConfig.isStructureSentinel(existing)) {
			return;
		}
		long chunkKey = new ChunkPos(pos).toLong();
		t0 = stages != null ? System.nanoTime() : 0L;
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		if (stages != null) {
			stages.createNs += System.nanoTime() - t0;
			t0 = System.nanoTime();
		}
		chunk.addPlacedBlock(pos, TerritoryConfig.STRUCTURE_BLOCK_SENTINEL);
		if (stages != null) {
			stages.putNs += System.nanoTime() - t0;
			t0 = System.nanoTime();
		}
		persistChunkChange(chunkKey, chunk);
		if (stages != null) {
			stages.persistNs += System.nanoTime() - t0;
		}
	}

	/**
	 * 在已加载的 {@link LevelChunk} 上写 sentinel，不调用 {@code getChunk}。
	 */
	public void markStructureSentinel(LevelChunk levelChunk, BlockPos pos, TerritoryPerf.StageNanos stages) {
		long t0 = stages != null ? System.nanoTime() : 0L;
		ChunkTerritoryData existingData = ChunkTerritoryAccess.getIfPresent(levelChunk);
		UUID existing = existingData == null ? null : existingData.getPlacedBlockOwner(pos);
		if (stages != null) {
			stages.lookupNs += System.nanoTime() - t0;
		}
		if (existing != null && !TerritoryConfig.isStructureSentinel(existing)) {
			return;
		}
		long chunkKey = levelChunk.getPos().toLong();
		t0 = stages != null ? System.nanoTime() : 0L;
		ChunkTerritoryData chunk = ChunkTerritoryAccess.getOrCreate(levelChunk);
		if (stages != null) {
			stages.createNs += System.nanoTime() - t0;
			t0 = System.nanoTime();
		}
		chunk.addPlacedBlock(pos, TerritoryConfig.STRUCTURE_BLOCK_SENTINEL);
		if (stages != null) {
			stages.putNs += System.nanoTime() - t0;
			t0 = System.nanoTime();
		}
		persistChunkChange(levelChunk, chunkKey, chunk);
		if (stages != null) {
			stages.persistNs += System.nanoTime() - t0;
		}
	}

	public void claimStructureBlock(BlockPos pos, UUID owner) {
		long chunkKey = new ChunkPos(pos).toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		chunk.addPlacedBlock(pos, owner);
		persistChunkChange(chunkKey, chunk);
		markChunkDirty(chunkKey);
	}

	public void onBlockRemoved(BlockPos pos) {
		long chunkKey = new ChunkPos(pos).toLong();
		ChunkTerritoryData chunk = getChunk(chunkKey);
		if (chunk == null || chunk.getPlacedBlockOwner(pos) == null) {
			return;
		}

		chunk.removePlacedBlock(pos);
		persistChunkChange(chunkKey, chunk);
		maybeRemoveEmptyChunk(chunkKey, chunk);
		markChunkDirty(chunkKey);
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		PlayerBlockStatus.LOGGER.info(
				"[pbs] tracked block removed at ({}, {}, {}) chunk=[{}, {}] placedCount={} dirtyCount={}",
				pos.getX(),
				pos.getY(),
				pos.getZ(),
				chunkPos.x,
				chunkPos.z,
				chunk.getPlacedBlocks().size(),
				dimensionData.getDirtyChunkKeys().size()
		);
	}

	public void onPlayerStay(ServerLevel level, UUID playerId, ChunkPos chunkPos, OrganizationProvider orgProvider) {
		long chunkKey = chunkPos.toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		UUID scoreEntity = resolveScoreEntity(level, playerId, orgProvider);
		chunk.accumulateStayScore(scoreEntity, TerritoryConfig.stayScorePerInterval);
		persistChunkChange(chunkKey, chunk);
	}

	public void onPlayerDeath(ServerLevel level, UUID playerId, ChunkPos chunkPos, OrganizationProvider orgProvider) {
		long chunkKey = chunkPos.toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		UUID scoreEntity = resolveScoreEntity(level, playerId, orgProvider);
		chunk.addDeathPenalty(scoreEntity, TerritoryConfig.deathPenalty);
		persistChunkChange(chunkKey, chunk);
		markChunkDirty(chunkKey);
	}

	public void transferPlayerToOrg(UUID playerId, UUID orgId) {
		remapOwnedChunks(playerId, orgId);
		dimensionData.getEntityChunkIndex().transferPlayerToOrg(playerId, orgId);
	}

	public void remapOrganization(UUID from, UUID to) {
		remapOwnedChunks(from, to);
		dimensionData.getEntityChunkIndex().mergeOrganization(from, to);
	}

	/** 只读查询：不加载 chunk；未加载或不含领土数据时 empty。 */
	public Optional<ChunkTerritoryData> queryChunk(ChunkPos chunkPos) {
		return Optional.ofNullable(getChunkIfLoaded(chunkPos.toLong()));
	}

	/**
	 * 在切比雪夫半径内强制写入区块状态与/或归属（组织/玩家 UUID）。
	 * {@link luowei.player_block_status.lib.api.PlayerBlockStatusLib#forceSetChunks} 的写入实现。
	 * {@code state == null} 表示不改状态；{@code updateOwner == false} 表示不改归属；
	 * {@code updateOwner == true} 时将 {@code occupyingOrg} 设为 {@code owner}（可为 null 清空）。
	 * 写入后标脏（维度 dirtyChunkKeys+epoch），并增量更新占领索引。
	 *
	 * @return 实际被改写的区块数量
	 */
	public int forceSetChunks(ChunkPos center, int radiusChunks, ChunkState state, boolean updateOwner, UUID owner) {
		if (radiusChunks < 0) {
			throw new IllegalArgumentException("radiusChunks must be >= 0");
		}
		if (state == null && !updateOwner) {
			return 0;
		}

		int changed = 0;
		Set<Long> changedKeys = new HashSet<>();
		for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
			for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) > radiusChunks) {
					continue;
				}
				ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
				long chunkKey = pos.toLong();
				ChunkTerritoryData existing = ChunkTerritoryAccess.getIfPresent(level, pos);
				ChunkState currentState = existing == null ? ChunkState.NATURAL : existing.getState();
				UUID currentOwner = existing == null ? null : existing.getOccupyingOrg();

				boolean changeState = state != null && state != currentState;
				boolean changeOwner = updateOwner && (owner == null ? currentOwner != null : !owner.equals(currentOwner));
				if (!changeState && !changeOwner) {
					continue;
				}
				if (currentState.isDemon() && (state == null || state != ChunkState.DEMON)) {
					continue;
				}

				ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
				if (changeState) {
					chunk.setState(state);
					if (state == ChunkState.DEMON) {
						chunk.setOccupyingOrg(null);
					}
				}
				if (changeOwner && state != ChunkState.DEMON && chunk.getState() != ChunkState.DEMON) {
					chunk.setOccupyingOrg(owner);
				}
				persistChunkChange(chunkKey, chunk);
				markChunkDirty(chunkKey);
				changedKeys.add(chunkKey);
				changed++;
			}
		}

		EntityChunkIndex index = getEntityChunkIndex();
		for (long chunkKey : changedKeys) {
			ChunkTerritoryData chunk = getChunk(chunkKey);
			if (chunk == null) {
				index.replaceChunk(chunkKey, ChunkState.NATURAL, null);
			} else {
				index.replaceChunk(chunkKey, chunk.getState(), chunk.getOccupyingOrg());
			}
		}
		return changed;
	}

	void removeEmptyChunk(long chunkKey) {
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		ChunkTerritoryData chunk = getChunk(chunkKey);
		if (chunk == null) {
			untrackActiveChunk(chunkKey, true, "removeEmptyChunk null");
			return;
		}

		ChunkTerritoryAccess.clearIfEmpty(level, chunkPos, chunk);
		if (!chunk.hasTerritoryData()) {
			untrackActiveChunk(chunkKey, true, "removeEmptyChunk empty");
		}
	}

	/**
	 * 只处理该实体 OCCUPIED ∪ BORDER 索引中的区块，避免扫描全部 active 并 force-load。
	 * 索引为空则跳过迭代。未加载的索引区块会按需加载。
	 */
	private void remapOwnedChunks(UUID from, UUID to) {
		Set<Long> affectedChunks = dimensionData.getEntityChunkIndex().getOwnedChunks(from);
		if (affectedChunks.isEmpty()) {
			return;
		}
		for (long chunkKey : affectedChunks) {
			ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
			chunk.remapEntitySilent(from, to);
			persistChunkChange(chunkKey, chunk);
		}
	}

	private void maybeRemoveEmptyChunk(long chunkKey, ChunkTerritoryData chunk) {
		if (!chunk.hasTerritoryData()) {
			removeEmptyChunk(chunkKey);
		}
	}

	void persistChunkChange(long chunkKey, ChunkTerritoryData chunk) {
		boolean tracking = chunk.hasTerritoryData();
		logPersistChunkChange(chunkKey, chunk, tracking);
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		if (tracking) {
			trackActiveChunk(chunkKey);
			syncDemonIndex(chunkKey, chunk);
			ChunkTerritoryAccess.markDirty(level, chunkPos);
			return;
		}

		ChunkTerritoryAccess.clearIfEmpty(level, chunkPos, chunk);
		untrackActiveChunk(chunkKey, true, "persist empty");
	}

	void persistChunkChange(LevelChunk levelChunk, long chunkKey, ChunkTerritoryData chunk) {
		boolean tracking = chunk.hasTerritoryData();
		logPersistChunkChange(chunkKey, chunk, tracking);
		if (tracking) {
			trackActiveChunk(chunkKey);
			syncDemonIndex(chunkKey, chunk);
			ChunkTerritoryAccess.markDirty(levelChunk);
			return;
		}

		ChunkTerritoryAccess.clearIfEmpty(levelChunk, chunk);
		untrackActiveChunk(chunkKey, true, "persist empty");
	}

	private void syncDemonIndex(long chunkKey, ChunkTerritoryData chunk) {
		if (chunk.getState() == ChunkState.DEMON) {
			dimensionData.addDemonChunk(chunkKey);
		} else {
			dimensionData.removeDemonChunk(chunkKey);
		}
	}

	private void trackActiveChunk(long chunkKey) {
		if (dimensionData.getActiveChunkKeys().add(chunkKey)) {
			dimensionData.setDirty();
			ChunkPos chunkPos = new ChunkPos(chunkKey);
			PlayerBlockStatus.LOGGER.debug(
					"[pbs persist] trackActiveChunk newly added cx={} cz={} key={} activeSize={}",
					chunkPos.x,
					chunkPos.z,
					chunkKey,
					dimensionData.getActiveChunkKeys().size()
			);
		}
	}

	/**
	 * 附件是真相；没附件即自然，倒排索引应删掉该 key。
	 * 读不到就保留索引是找补，会掩盖持久化问题、留下幽灵边界。
	 * 应先完善 chunk attachment / 维度 SavedData 落盘；持久化可靠后再考虑找补。
	 * 因此暂时恢复「读不到 / 空附件 → 当自然并清 occupied/border 索引」。
	 *
	 * @param clearOwnedIndex 存活路径应为 true（清 occupied/border）。{@code false} 是找补：读不到 attachment 时保留索引。
	 * @param reason          调用原因，写入 persist 日志
	 */
	private void untrackActiveChunk(long chunkKey, boolean clearOwnedIndex, String reason) {
		boolean wasActive = dimensionData.getActiveChunkKeys().remove(chunkKey);
		if (wasActive) {
			dimensionData.setDirty();
		}
		boolean demonRemoved = dimensionData.removeDemonChunk(chunkKey);
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		PlayerBlockStatus.LOGGER.debug(
				"[pbs persist] untrackActiveChunk cx={} cz={} key={} reason={} clearOwnedIndex={} wasActive={} demonRemoved={} activeSize={}",
				chunkPos.x,
				chunkPos.z,
				chunkKey,
				reason,
				clearOwnedIndex,
				wasActive,
				demonRemoved,
				dimensionData.getActiveChunkKeys().size()
		);
		if (clearOwnedIndex) {
			getEntityChunkIndex().replaceChunk(chunkKey, ChunkState.NATURAL, null);
		}
	}

	private ChunkTerritoryData requireTerritoryOrLog(long chunkKey, String via, ChunkTerritoryData chunk) {
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		if (chunk == null) {
			PlayerBlockStatus.LOGGER.debug(
					"[pbs persist] {} returning null (attachment null) cx={} cz={} key={}",
					via,
					chunkPos.x,
					chunkPos.z,
					chunkKey
			);
			return null;
		}
		if (!chunk.hasTerritoryData()) {
			PlayerBlockStatus.LOGGER.debug(
					"[pbs persist] {} returning null (!hasTerritoryData) cx={} cz={} key={} state={} occupyingOrg={} placedBlocks={}",
					via,
					chunkPos.x,
					chunkPos.z,
					chunkKey,
					chunk.getState(),
					chunk.getOccupyingOrg(),
					chunk.getPlacedBlocks().size()
			);
			return null;
		}
		return chunk;
	}

	private void logPersistChunkChange(long chunkKey, ChunkTerritoryData chunk, boolean tracking) {
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		PlayerBlockStatus.LOGGER.debug(
				"[pbs persist] persistChunkChange cx={} cz={} key={} state={} occupyingOrg={} hasTerritoryData={} action={} placedBlocks={}",
				chunkPos.x,
				chunkPos.z,
				chunkKey,
				chunk.getState(),
				chunk.getOccupyingOrg(),
				chunk.hasTerritoryData(),
				tracking ? "track+markDirty" : "clearAttachment+untrack",
				chunk.getPlacedBlocks().size()
		);
	}

	private UUID resolveScoreEntity(ServerLevel level, UUID playerId, OrganizationProvider orgProvider) {
		if (orgProvider == null || playerId == null) {
			return playerId;
		}
		return orgProvider.getOrganizationId(level.getServer(), playerId).orElse(playerId);
	}
}
