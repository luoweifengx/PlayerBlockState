package luowei.player_block_status.lib.debug;

import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.api.PlayerBlockStatusLib;
import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.ChunkTerritoryData;

/**
 * 程序化查询区块领土信息的工具类。
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
				.map(data -> toInfo(chunkPos, data))
				.orElse(new ChunkInfo(chunkPos, ChunkState.NATURAL, null, 0, Map.of(), Map.of()));
	}

	public static ChunkInfo toInfo(ChunkPos chunkPos, ChunkTerritoryData data) {
		return new ChunkInfo(
				chunkPos,
				data.getState(),
				data.getOccupyingOrg(),
				data.getPlacedBlocks().size(),
				Map.copyOf(data.getCachedScores()),
				Map.copyOf(data.getStayScores())
		);
	}
}
