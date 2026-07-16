package luowei.player_block_status.lib.chunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
	private final Set<Long> dirtyChunkKeys = new HashSet<>();

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

	public void markChunkDirty(long chunkKey) {
		if (dirtyChunkKeys.add(chunkKey)) {
			PlayerBlockStatus.LOGGER.debug(
					"[pbs daily] chunk {} marked dirty (dirtyCount={}, activeCount={})",
					new ChunkPos(chunkKey),
					dirtyChunkKeys.size(),
					dimensionData.getActiveChunkKeys().size()
			);
		}
	}

	public Set<Long> getDirtyChunkKeys() {
		return dirtyChunkKeys;
	}

	public int getActiveChunkKeyCount() {
		return dimensionData.getActiveChunkKeys().size();
	}

	public Set<Long> getActiveChunkKeys() {
		return Set.copyOf(dimensionData.getActiveChunkKeys());
	}

	/**
	 * 解析本次每日重算应处理的区块：优先 dirty，否则回退到持久化的 activeChunkKeys。
	 * dirty 集合仅存在于内存，重启后会丢失，因此必须回退。
	 */
	public Set<Long> resolveRecomputeChunkKeys() {
		Set<Long> keys = new HashSet<>(dirtyChunkKeys);
		if (!keys.isEmpty()) {
			return keys;
		}
		keys.addAll(dimensionData.getActiveChunkKeys());
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

	public void finishDailyRefresh() {
		dimensionData.finishDailyRefresh();
	}

	public void cancelDailyRefreshInProgress() {
		dimensionData.cancelDailyRefreshInProgress();
	}

	public void onBlockPlaced(ServerLevel level, BlockPos pos, UUID playerId, OrganizationProvider orgProvider) {
		UUID scoreEntity = resolveScoreEntity(level, playerId, orgProvider);
		claimStructureIfNeeded(level, pos, scoreEntity);

		long chunkKey = new ChunkPos(pos).toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		chunk.addPlacedBlock(pos, scoreEntity);
		persistChunkChange(chunkKey, chunk);
		markChunkDirty(chunkKey);
		PlayerBlockStatus.LOGGER.info(
				"[pbs] block placed at {} in chunk {} by {} (placedCount={}, dirtyCount={})",
				pos,
				new ChunkPos(chunkKey),
				scoreEntity,
				chunk.getPlacedBlocks().size(),
				dirtyChunkKeys.size()
		);
	}

	public UUID getPlacedBlockOwner(BlockPos pos) {
		ChunkTerritoryData chunk = ChunkTerritoryAccess.getIfPresent(level, new ChunkPos(pos));
		if (chunk == null) {
			return null;
		}
		return chunk.getPlacedBlockOwner(pos);
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
		if (chunk == null) {
			return;
		}

		chunk.removePlacedBlock(pos);
		persistChunkChange(chunkKey, chunk);
		maybeRemoveEmptyChunk(chunkKey, chunk);
		markChunkDirty(chunkKey);
	}

	public void onPlayerStay(ServerLevel level, UUID playerId, ChunkPos chunkPos, OrganizationProvider orgProvider) {
		long chunkKey = chunkPos.toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		UUID scoreEntity = resolveScoreEntity(level, playerId, orgProvider);
		chunk.accumulateStayScore(scoreEntity, TerritoryConfig.stayScorePerInterval);
		persistChunkChange(chunkKey, chunk);
		markChunkDirty(chunkKey);
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
		Set<Long> affectedChunks = findChunksForEntity(playerId);
		for (long chunkKey : affectedChunks) {
			ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
			chunk.remapEntitySilent(playerId, orgId);
			persistChunkChange(chunkKey, chunk);
		}
		dimensionData.getEntityChunkIndex().transferPlayerToOrg(playerId, orgId);
	}

	public void remapOrganization(UUID from, UUID to) {
		Set<Long> affectedChunks = findChunksForEntity(from);
		for (long chunkKey : affectedChunks) {
			ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
			chunk.remapEntitySilent(from, to);
			persistChunkChange(chunkKey, chunk);
		}
		dimensionData.getEntityChunkIndex().mergeOrganization(from, to);
	}

	public Optional<ChunkTerritoryData> queryChunk(ChunkPos chunkPos) {
		ChunkTerritoryData chunk = getChunkForRecompute(chunkPos.toLong());
		return Optional.ofNullable(chunk);
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

	private Set<Long> findChunksForEntity(UUID entityId) {
		Set<Long> affectedChunks = new HashSet<>();
		for (long chunkKey : dimensionData.getActiveChunkKeys()) {
			ChunkTerritoryData chunk = getChunk(chunkKey);
			if (chunk != null && chunk.referencesEntity(entityId)) {
				affectedChunks.add(chunkKey);
			}
		}
		return affectedChunks;
	}

	private void maybeRemoveEmptyChunk(long chunkKey, ChunkTerritoryData chunk) {
		if (!chunk.hasTerritoryData()) {
			removeEmptyChunk(chunkKey);
		}
	}

	private void claimStructureIfNeeded(ServerLevel level, BlockPos placedPos, UUID scoreEntity) {
		Iterator<StructureBounds> iterator = dimensionData.getPendingStructures().iterator();
		while (iterator.hasNext()) {
			StructureBounds bounds = iterator.next();
			if (!bounds.contains(placedPos)) {
				continue;
			}

			StructureClaimProcessor.enqueue(level, this, bounds, placedPos, scoreEntity);
			iterator.remove();
			dimensionData.setDirty();
		}
	}

	private void persistChunkChange(long chunkKey, ChunkTerritoryData chunk) {
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		if (chunk.hasTerritoryData()) {
			trackActiveChunk(chunkKey);
			ChunkTerritoryAccess.markDirty(level, chunkPos);
			return;
		}

		ChunkTerritoryAccess.clearIfEmpty(level, chunkPos, chunk);
		untrackActiveChunk(chunkKey);
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
	}

	private UUID resolveScoreEntity(ServerLevel level, UUID playerId, OrganizationProvider orgProvider) {
		return level.getServer().getPlayerList().getPlayer(playerId) != null
				? orgProvider.getOrganizationId(level.getServer().getPlayerList().getPlayer(playerId)).orElse(playerId)
				: playerId;
	}
}
