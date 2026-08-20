package luowei.player_block_status.lib.chunk;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.api.OrganizationProvider;
import luowei.player_block_status.lib.api.SafeBiomeChecker;
import luowei.player_block_status.lib.chunk.TerritoryDailyProcessor.ScheduleAttempt;

/**
 * 维度领土管理器：事件只记账与标脏，重算仅在每日日出对 dirty 区块异步执行。
 */
public final class RegionManager {
	private RegionManager() {
	}

	public static void onBlockPlaced(ServerLevel level, BlockPos pos, ServerPlayer player, OrganizationProvider orgProvider) {
		WorldRegionData data = WorldRegionData.get(level);
		data.onBlockPlaced(level, pos, player.getUUID(), orgProvider);
	}

	/**
	 * 删分唯一出口：从 {@code placed_blocks} 去掉该格（幂等）。
	 * Mixin 与 {@link luowei.player_block_status.lib.api.PlayerBlockStatusLib#notifyTrackedBlockRemoved}
	 * 均汇入此处。仅服务端逻辑线程生效。
	 */
	public static void onBlockRemoved(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}
		if (!level.getServer().isSameThread()) {
			return;
		}
		WorldRegionData.get(level).onBlockRemoved(pos);
	}

	public static void onPlayerStay(ServerLevel level, ServerPlayer player, ChunkPos chunkPos, OrganizationProvider orgProvider) {
		WorldRegionData data = WorldRegionData.get(level);
		data.onPlayerStay(level, player.getUUID(), chunkPos, orgProvider);
	}

	public static void onPlayerDeath(ServerLevel level, ServerPlayer player, OrganizationProvider orgProvider) {
		ChunkPos chunkPos = player.chunkPosition();
		WorldRegionData data = WorldRegionData.get(level);
		data.onPlayerDeath(level, player.getUUID(), chunkPos, orgProvider);
	}

	public static void remapOrganization(ServerLevel level, UUID from, UUID to) {
		WorldRegionData.get(level).remapOrganization(from, to);
	}

	public static void transferPlayerToOrg(ServerLevel level, UUID playerId, UUID orgId) {
		WorldRegionData.get(level).transferPlayerToOrg(playerId, orgId);
	}

	public static void registerStructure(ServerLevel level, StructureBounds bounds) {
		WorldRegionData data = WorldRegionData.get(level);
		data.registerStructure(bounds);
	}

	/**
	 * 调试用：强制设置半径内区块状态与/或归属。详见 {@link WorldRegionData#forceSetChunks}。
	 */
	public static int forceSetChunks(
			ServerLevel level,
			ChunkPos center,
			int radiusChunks,
			ChunkState state,
			boolean updateOwner,
			UUID owner
	) {
		return WorldRegionData.get(level).forceSetChunks(center, radiusChunks, state, updateOwner, owner);
	}

	/**
	 * 每日重算触发：{@code currentDay > lastDailyDay} 且 {@code timeOfDay >= dailyRefreshTime}。
	 * 不再要求精确落在单个 tick，避免服务器 lag 跳过日出时刻。
	 */
	public static void tickDaily(ServerLevel level, OrganizationProvider orgProvider, SafeBiomeChecker safeChecker) {
		long dayTime = level.getDayTime();
		long currentDay = dayTime / 24000L;
		int timeOfDay = (int) (dayTime % 24000L);
		WorldRegionData data = WorldRegionData.get(level);
		long lastDailyDay = data.getLastDailyDay();

		if (currentDay <= lastDailyDay) {
			return;
		}

		if (timeOfDay < TerritoryConfig.dailyRefreshTime) {
			return;
		}

		// PlayerBlockStatus.LOGGER.info(
		// 		"[pbs daily] day rollover ready for {}: currentDay={}, lastDailyDay={}, timeOfDay={}, refreshTime={}, dirtyChunks={}, activeChunks={}",
		// 		level.dimension().location(),
		// 		currentDay,
		// 		lastDailyDay,
		// 		timeOfDay,
		// 		TerritoryConfig.dailyRefreshTime,
		// 		data.getDirtyChunkKeys().size(),
		// 		data.getActiveChunkKeyCount()
		// );

		TerritoryDailyProcessor.trySchedule(level, orgProvider, safeChecker, currentDay);
	}

	/** 调试用：立即调度一次标脏区块重算，可同一天重复执行。 */
	public static ScheduleAttempt forceDailyRefresh(
			ServerLevel level,
			OrganizationProvider orgProvider,
			SafeBiomeChecker safeChecker
	) {
		long currentDay = level.getDayTime() / 24000L;
		return TerritoryDailyProcessor.tryScheduleForced(level, orgProvider, safeChecker, currentDay);
	}
}
