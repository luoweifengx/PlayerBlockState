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

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.api.OrganizationProvider;
import luowei.player_block_status.lib.debug.MapExportTrace;

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
		if (trace != null) {
			trace.step(
					"DimensionTerritoryData loaded in %dms (activeChunkKeys=%d)",
					(System.nanoTime() - loadStart) / 1_000_000L,
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
		}
		return chunk;
	}

	public ChunkTerritoryData getChunk(long chunkKey) {
		ChunkTerritoryData chunk = ChunkTerritoryAccess.getIfPresent(level, new ChunkPos(chunkKey));
		if (chunk == null || !chunk.hasTerritoryData()) {
			return null;
		}
		return chunk;
	}

	/** 每日重算/地图读取：只要 attachment 有数据即返回，必要时触发 chunk 加载。 */
	public ChunkTerritoryData getChunkForRecompute(long chunkKey) {
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		level.getChunk(chunkPos.x, chunkPos.z);
		ChunkTerritoryData chunk = ChunkTerritoryAccess.getIfPresent(level, chunkPos);
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
			untrackActiveChunk(chunkKey);
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
					"[pbs daily] chunk {} marked dirty (dirtyCount={}, activeCount={})",
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
	 */
	public void markStructureSentinel(BlockPos pos) {
		UUID existing = getPlacedBlockOwner(pos);
		if (existing != null && !TerritoryConfig.isStructureSentinel(existing)) {
			return;
		}
		long chunkKey = new ChunkPos(pos).toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		chunk.addPlacedBlock(pos, TerritoryConfig.STRUCTURE_BLOCK_SENTINEL);
		persistChunkChange(chunkKey, chunk);
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

	public Optional<ChunkTerritoryData> queryChunk(ChunkPos chunkPos) {
		ChunkTerritoryData chunk = getChunkForRecompute(chunkPos.toLong());
		return Optional.ofNullable(chunk);
	}

	/**
	 * 调试用：在切比雪夫半径内强制写入区块状态与/或归属（组织/玩家 UUID）。
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
			untrackActiveChunk(chunkKey);
			return;
		}

		ChunkTerritoryAccess.clearIfEmpty(level, chunkPos, chunk);
		if (!chunk.hasTerritoryData()) {
			untrackActiveChunk(chunkKey);
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
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		if (chunk.hasTerritoryData()) {
			trackActiveChunk(chunkKey);
			syncDemonIndex(chunkKey, chunk);
			ChunkTerritoryAccess.markDirty(level, chunkPos);
			return;
		}

		ChunkTerritoryAccess.clearIfEmpty(level, chunkPos, chunk);
		untrackActiveChunk(chunkKey);
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
		}
	}

	private void untrackActiveChunk(long chunkKey) {
		if (dimensionData.getActiveChunkKeys().remove(chunkKey)) {
			dimensionData.setDirty();
		}
		dimensionData.removeDemonChunk(chunkKey);
		getEntityChunkIndex().replaceChunk(chunkKey, ChunkState.NATURAL, null);
	}

	private UUID resolveScoreEntity(ServerLevel level, UUID playerId, OrganizationProvider orgProvider) {
		if (orgProvider == null || playerId == null) {
			return playerId;
		}
		return orgProvider.getOrganizationId(level.getServer(), playerId).orElse(playerId);
	}
}
