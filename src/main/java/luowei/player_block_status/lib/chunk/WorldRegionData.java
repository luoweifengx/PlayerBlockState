package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.api.OrganizationProvider;

/**
 * 维度级领土数据持久化，以区块为单位存储。
 */
public class WorldRegionData extends SavedData {
	private static final String DATA_ID = "player_block_status_territory";

	public static final Codec<WorldRegionData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			TerritoryCodec.longKeyMap(ChunkTerritoryData.CODEC).fieldOf("chunks").forGetter(data -> data.chunks),
			StructureBounds.CODEC.listOf().fieldOf("structures").forGetter(data -> data.pendingStructures),
			Codec.LONG.fieldOf("last_daily_day").forGetter(data -> data.lastDailyDay)
	).apply(instance, (chunks, structures, lastDailyDay) -> {
		WorldRegionData data = new WorldRegionData();
		data.chunks.putAll(chunks);
		data.pendingStructures.addAll(structures);
		data.lastDailyDay = lastDailyDay;
		data.migrateLegacyStayData();
		data.entityChunkIndex.rebuildOccupiedFrom(data.chunks);
		data.indexReady = true;
		return data;
	}));

	public static final SavedDataType<WorldRegionData> TYPE = new SavedDataType<>(
			DATA_ID,
			context -> new WorldRegionData(),
			context -> WorldRegionData.CODEC,
			null
	);

	private final Map<Long, ChunkTerritoryData> chunks = new HashMap<>();
	private final List<StructureBounds> pendingStructures = new ArrayList<>();
	private final Set<Long> dirtyChunkKeys = new HashSet<>();
	private final EntityChunkIndex entityChunkIndex = new EntityChunkIndex();
	private long lastDailyDay = -1;
	private boolean indexReady;
	private boolean dailyRefreshInProgress;

	public static WorldRegionData get(ServerLevel level) {
		WorldRegionData data = level.getDataStorage().computeIfAbsent(TYPE);
		data.ensureIndexReady();
		return data;
	}

	private void ensureIndexReady() {
		if (!indexReady) {
			migrateLegacyStayData();
			entityChunkIndex.rebuildOccupiedFrom(chunks);
			indexReady = true;
		}
	}

	/** 旧存档将停留分写入 score_modifiers，迁移到 stay_scores 以便每日清零。 */
	private void migrateLegacyStayData() {
		for (ChunkTerritoryData chunk : chunks.values()) {
			if (!chunk.getStayScores().isEmpty() || chunk.getState() == ChunkState.DEATH) {
				continue;
			}

			chunk.getScoreModifiers().entrySet().removeIf(entry -> {
				if (entry.getValue() <= 0) {
					return false;
				}
				chunk.getStayScores().merge(entry.getKey(), entry.getValue(), Integer::sum);
				return true;
			});
		}
	}

	public ChunkTerritoryData getOrCreateChunk(long chunkKey) {
		return chunks.computeIfAbsent(chunkKey, key -> ChunkTerritoryData.createEmpty());
	}

	public ChunkTerritoryData getChunk(long chunkKey) {
		return chunks.get(chunkKey);
	}

	public Map<Long, ChunkTerritoryData> getAllChunks() {
		return chunks;
	}

	public EntityChunkIndex getEntityChunkIndex() {
		return entityChunkIndex;
	}

	public void markChunkDirty(long chunkKey) {
		dirtyChunkKeys.add(chunkKey);
	}

	public Set<Long> getDirtyChunkKeys() {
		return dirtyChunkKeys;
	}

	public List<StructureBounds> getPendingStructures() {
		return pendingStructures;
	}

	public void registerStructure(StructureBounds bounds) {
		pendingStructures.add(bounds);
		setDirty();
	}

	public long getLastDailyDay() {
		return lastDailyDay;
	}

	public boolean tryBeginDailyRefresh(long currentDay) {
		if (dailyRefreshInProgress || currentDay <= lastDailyDay) {
			return false;
		}
		dailyRefreshInProgress = true;
		lastDailyDay = currentDay;
		setDirty();
		return true;
	}

	public void finishDailyRefresh() {
		dailyRefreshInProgress = false;
	}

	public void cancelDailyRefreshInProgress() {
		dailyRefreshInProgress = false;
	}

	public void onBlockPlaced(ServerLevel level, BlockPos pos, UUID playerId, OrganizationProvider orgProvider) {
		UUID scoreEntity = resolveScoreEntity(level, playerId, orgProvider);
		claimStructureIfNeeded(level, pos, scoreEntity);

		long chunkKey = new ChunkPos(pos).toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		chunk.addPlacedBlock(pos, scoreEntity);
		markChunkDirty(chunkKey);
		setDirty();
	}

	public void onBlockRemoved(BlockPos pos) {
		long chunkKey = new ChunkPos(pos).toLong();
		ChunkTerritoryData chunk = chunks.get(chunkKey);
		if (chunk == null) {
			return;
		}

		chunk.removePlacedBlock(pos);
		maybeRemoveEmptyChunk(chunkKey, chunk);
		markChunkDirty(chunkKey);
		setDirty();
	}

	public void onPlayerStay(ServerLevel level, UUID playerId, ChunkPos chunkPos, OrganizationProvider orgProvider) {
		long chunkKey = chunkPos.toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		UUID scoreEntity = resolveScoreEntity(level, playerId, orgProvider);
		chunk.accumulateStayScore(scoreEntity, TerritoryConfig.stayScorePerInterval);
		setDirty();
	}

	public void onPlayerDeath(ServerLevel level, UUID playerId, ChunkPos chunkPos, OrganizationProvider orgProvider) {
		long chunkKey = chunkPos.toLong();
		ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
		UUID scoreEntity = resolveScoreEntity(level, playerId, orgProvider);
		chunk.addDeathPenalty(scoreEntity, TerritoryConfig.deathPenalty);
		markChunkDirty(chunkKey);
		setDirty();
	}

	public void transferPlayerToOrg(UUID playerId, UUID orgId) {
		Set<Long> affectedChunks = new HashSet<>(entityChunkIndex.getChunks(playerId));
		for (long chunkKey : affectedChunks) {
			ChunkTerritoryData chunk = chunks.get(chunkKey);
			if (chunk == null) {
				continue;
			}
			chunk.remapEntity(playerId, orgId);
			if (chunk.isDirty()) {
				markChunkDirty(chunkKey);
			}
		}
		setDirty();
	}

	public void remapOrganization(UUID from, UUID to) {
		Set<Long> affectedChunks = new HashSet<>(entityChunkIndex.getChunks(from));
		for (long chunkKey : affectedChunks) {
			ChunkTerritoryData chunk = chunks.get(chunkKey);
			if (chunk == null) {
				continue;
			}
			chunk.remapEntity(from, to);
			if (chunk.isDirty()) {
				markChunkDirty(chunkKey);
			}
		}
		setDirty();
	}

	private void maybeRemoveEmptyChunk(long chunkKey, ChunkTerritoryData chunk) {
		if (!chunk.hasTerritoryData()) {
			chunks.remove(chunkKey);
		}
	}

	private void claimStructureIfNeeded(ServerLevel level, BlockPos placedPos, UUID scoreEntity) {
		Iterator<StructureBounds> iterator = pendingStructures.iterator();
		while (iterator.hasNext()) {
			StructureBounds bounds = iterator.next();
			if (!bounds.contains(placedPos)) {
				continue;
			}

			for (BlockPos blockPos : bounds.resolvedBlocks()) {
				long chunkKey = new ChunkPos(blockPos).toLong();
				ChunkTerritoryData chunk = getOrCreateChunk(chunkKey);
				chunk.addPlacedBlock(blockPos, scoreEntity);
				markChunkDirty(chunkKey);
			}
			iterator.remove();
			PlayerBlockStatus.LOGGER.info("Structure {} claimed by {}", bounds.id(), scoreEntity);
		}
	}

	private UUID resolveScoreEntity(ServerLevel level, UUID playerId, OrganizationProvider orgProvider) {
		return level.getServer().getPlayerList().getPlayer(playerId) != null
				? orgProvider.getOrganizationId(level.getServer().getPlayerList().getPlayer(playerId)).orElse(playerId)
				: playerId;
	}

	public Optional<ChunkTerritoryData> queryChunk(ChunkPos chunkPos) {
		return Optional.ofNullable(chunks.get(chunkPos.toLong()));
	}
}
