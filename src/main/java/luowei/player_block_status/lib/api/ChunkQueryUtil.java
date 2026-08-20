package luowei.player_block_status.lib.api;

import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.chunk.ChunkState;

/**
 * 程序化查询区块领土信息的工具类（只读）。
 */
public final class ChunkQueryUtil {
	private ChunkQueryUtil() {
	}

	public record ChunkInfo(
			ChunkPos chunkPos,
			ChunkState state,
			UUID occupyingOrg,
			int placedBlockCount,
			Map<UUID, Integer> scores,
			Map<UUID, Integer> pendingStayScores
	) {
		@Override
		public String toString() {
			return "ChunkInfo{pos=" + chunkPos + ", state=" + state + " (" + state.getId() + ")"
					+ ", org=" + occupyingOrg + ", blocks=" + placedBlockCount
					+ ", scores=" + scores + ", pendingStay=" + pendingStayScores + "}";
		}
	}

	public static ChunkInfo query(ServerLevel level, ChunkPos chunkPos) {
		return PlayerBlockStatusLib.queryChunk(level, chunkPos)
				.map(ChunkQueryUtil::toInfo)
				.orElse(new ChunkInfo(chunkPos, ChunkState.NATURAL, null, 0, Map.of(), Map.of()));
	}

	public static ChunkInfo toInfo(ChunkTerritoryView view) {
		return new ChunkInfo(
				view.getChunkPos(),
				view.getState(),
				view.getOccupyingOrg(),
				view.getPlacedBlockCount(),
				view.getScores(),
				view.getPendingStayScores()
		);
	}
}
