package luowei.player_block_status.lib.api;

import java.util.Map;
import java.util.UUID;

import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.ChunkTerritoryData;

/**
 * 区块领土只读视图，不暴露可变的 {@link ChunkTerritoryData} 内部结构。
 */
public final class ChunkTerritoryView {
	private final ChunkPos chunkPos;
	private final ChunkState state;
	private final UUID occupyingOrg;
	private final int placedBlockCount;
	private final Map<UUID, Integer> scores;
	private final Map<UUID, Integer> pendingStayScores;

	ChunkTerritoryView(
			ChunkPos chunkPos,
			ChunkState state,
			UUID occupyingOrg,
			int placedBlockCount,
			Map<UUID, Integer> scores,
			Map<UUID, Integer> pendingStayScores
	) {
		this.chunkPos = chunkPos;
		this.state = state;
		this.occupyingOrg = occupyingOrg;
		this.placedBlockCount = placedBlockCount;
		this.scores = scores;
		this.pendingStayScores = pendingStayScores;
	}

	public static ChunkTerritoryView natural(ChunkPos chunkPos) {
		return new ChunkTerritoryView(chunkPos, ChunkState.NATURAL, null, 0, Map.of(), Map.of());
	}

	public static ChunkTerritoryView from(ChunkPos chunkPos, ChunkTerritoryData data) {
		return new ChunkTerritoryView(
				chunkPos,
				data.getState(),
				data.getOccupyingOrg(),
				data.getPlacedBlocks().size(),
				Map.copyOf(data.getCachedScores()),
				Map.copyOf(data.getStayScores())
		);
	}

	public ChunkPos getChunkPos() {
		return chunkPos;
	}

	public ChunkState getState() {
		return state;
	}

	public UUID getOccupyingOrg() {
		return occupyingOrg;
	}

	public int getPlacedBlockCount() {
		return placedBlockCount;
	}

	public Map<UUID, Integer> getScores() {
		return scores;
	}

	public Map<UUID, Integer> getPendingStayScores() {
		return pendingStayScores;
	}
}
