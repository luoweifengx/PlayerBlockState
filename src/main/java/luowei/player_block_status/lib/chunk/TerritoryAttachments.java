package luowei.player_block_status.lib.chunk;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * 区块领土数据挂载到 {@link net.minecraft.world.level.chunk.LevelChunk}，随 chunk 独立存取。
 */
public final class TerritoryAttachments {
	public static final AttachmentType<ChunkTerritoryData> CHUNK_TERRITORY = AttachmentRegistry.create(
			PlayerBlockStatus.id("chunk_territory"),
			builder -> builder
					.initializer(ChunkTerritoryData::createEmpty)
					.persistent(ChunkTerritoryData.CODEC)
	);

	private TerritoryAttachments() {
	}

	public static void register() {
		// AttachmentRegistry.create registers eagerly; method exists for explicit init order.
	}
}
