package luowei.player_block_status.lib.chunk;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.api.OrganizationProvider;
import luowei.player_block_status.lib.api.SafeBiomeChecker;

/**
 * 维度领土管理器：事件只记账与标脏，全盘重算仅在每日日出异步执行。
 */
public final class RegionManager {
	private RegionManager() {
	}

	public static void onBlockPlaced(ServerLevel level, BlockPos pos, ServerPlayer player, OrganizationProvider orgProvider) {
		WorldRegionData data = WorldRegionData.get(level);
		data.onBlockPlaced(level, pos, player.getUUID(), orgProvider);
	}

	public static void onBlockRemoved(ServerLevel level, BlockPos pos) {
		WorldRegionData data = WorldRegionData.get(level);
		data.onBlockRemoved(pos);
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

	public static void tickDaily(ServerLevel level, OrganizationProvider orgProvider, SafeBiomeChecker safeChecker) {
		long day = level.getDayTime() / 24000L;
		int timeOfDay = (int) (level.getDayTime() % 24000L);

		if (timeOfDay != TerritoryConfig.dailyRefreshTime) {
			return;
		}

		TerritoryDailyProcessor.trySchedule(level, orgProvider, safeChecker, day);
	}
}
