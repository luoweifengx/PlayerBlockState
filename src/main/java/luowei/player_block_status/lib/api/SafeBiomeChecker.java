package luowei.player_block_status.lib.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 由消费模组提供：判断区块是否应处于安全状态（群系/地势）。
 */
@FunctionalInterface
public interface SafeBiomeChecker {
	boolean isSafeChunk(ServerLevel level, BlockPos chunkOrigin);

	SafeBiomeChecker NONE = (level, chunkOrigin) -> false;
}
