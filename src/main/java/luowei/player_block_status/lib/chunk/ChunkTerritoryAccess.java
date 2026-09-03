package luowei.player_block_status.lib.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * 通过 Fabric Data Attachment 读写单区块领土数据，变更时标记 chunk 待保存。
 */
public final class ChunkTerritoryAccess {
	private ChunkTerritoryAccess() {
	}

	public static ChunkTerritoryData getOrCreate(ServerLevel level, ChunkPos chunkPos) {
		LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
		return getOrCreate(chunk);
	}

	/** 已持有 {@link LevelChunk} 时取 attachment，不触发 {@code getChunk}。 */
	public static ChunkTerritoryData getOrCreate(LevelChunk chunk) {
		return chunk.getAttachedOrCreate(TerritoryAttachments.CHUNK_TERRITORY);
	}

	public static ChunkTerritoryData getIfPresent(ServerLevel level, ChunkPos chunkPos) {
		LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
		ChunkTerritoryData data = getIfPresent(chunk);
		if (data == null) {
			PlayerBlockStatus.LOGGER.debug(
					"[pbs persist] no attachment after getChunk cx={} cz={}",
					chunkPos.x,
					chunkPos.z
			);
		}
		return data;
	}

	/**
	 * 仅当区块已在内存时读取 attachment，不触发加载或生成。
	 * 未加载或无领土 attachment 返回 {@code null}。
	 */
	public static ChunkTerritoryData getIfLoaded(ServerLevel level, ChunkPos chunkPos) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
		if (chunk == null) {
			return null;
		}
		return getIfPresent(chunk);
	}

	/**
	 * 已持有 {@link LevelChunk} 时读取 attachment，不触发 {@code getChunk}。
	 * 无领土数据返回 {@code null}。
	 */
	public static ChunkTerritoryData getIfPresent(LevelChunk chunk) {
		return chunk.getAttached(TerritoryAttachments.CHUNK_TERRITORY);
	}

	public static void markDirty(ServerLevel level, ChunkPos chunkPos) {
		level.getChunk(chunkPos.x, chunkPos.z).markUnsaved();
	}

	public static void markDirty(LevelChunk chunk) {
		chunk.markUnsaved();
	}

	public static void clearIfEmpty(ServerLevel level, ChunkPos chunkPos, ChunkTerritoryData data) {
		if (data.hasTerritoryData()) {
			markDirty(level, chunkPos);
			return;
		}

		LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
		clearIfEmpty(chunk, data);
	}

	public static void clearIfEmpty(LevelChunk chunk, ChunkTerritoryData data) {
		if (data.hasTerritoryData()) {
			markDirty(chunk);
			return;
		}
		ChunkPos chunkPos = chunk.getPos();
		PlayerBlockStatus.LOGGER.debug(
				"[pbs persist] clearIfEmpty clearing attachment cx={} cz={}",
				chunkPos.x,
				chunkPos.z
		);
		chunk.setAttached(TerritoryAttachments.CHUNK_TERRITORY, null);
	}
}
