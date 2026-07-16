package luowei.player_block_status.lib.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 通过 Fabric Data Attachment 读写单区块领土数据，变更时标记 chunk 待保存。
 */
public final class ChunkTerritoryAccess {
	private ChunkTerritoryAccess() {
	}

	public static ChunkTerritoryData getOrCreate(ServerLevel level, ChunkPos chunkPos) {
		LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
		return chunk.getAttachedOrCreate(TerritoryAttachments.CHUNK_TERRITORY);
	}

	public static ChunkTerritoryData getIfPresent(ServerLevel level, ChunkPos chunkPos) {
		LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
		return chunk.getAttached(TerritoryAttachments.CHUNK_TERRITORY);
	}

	public static void markDirty(ServerLevel level, ChunkPos chunkPos) {
		level.getChunk(chunkPos.x, chunkPos.z).markUnsaved();
	}

	public static void clearIfEmpty(ServerLevel level, ChunkPos chunkPos, ChunkTerritoryData data) {
		if (data.hasTerritoryData()) {
			markDirty(level, chunkPos);
			return;
		}

		LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
		chunk.setAttached(TerritoryAttachments.CHUNK_TERRITORY, null);
	}
}
