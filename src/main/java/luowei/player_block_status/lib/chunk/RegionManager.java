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
 * 维度领土管理器：事件只记账与标脏，重算每 {@link TerritoryConfig#refreshIntervalTicks} tick 对 dirty 区块异步执行。
 */
public final class RegionManager {
	private RegionManager() {
	}

	public static void onBlockPlaced(ServerLevel level, BlockPos pos, ServerPlayer player, OrganizationProvider orgProvider) {
		if (player == null) {
			return;
		}
		onBlockPlaced(level, pos, player.getUUID(), orgProvider);
	}

	/**
	 * 放置计分唯一出口：写入 {@code placed_blocks}（同一格覆盖归属，不叠分）。
	 * Mixin 与 {@link luowei.player_block_status.lib.api.PlayerBlockStatusLib#notifyTrackedBlockPlaced}
	 * 均汇入此处。仅服务端逻辑线程生效。
	 * {@code ownerId} 为要归属的玩家 UUID；若该玩家在组织中，由 {@code orgProvider} 解析为组织 UUID。
	 */
	public static void onBlockPlaced(ServerLevel level, BlockPos pos, UUID ownerId, OrganizationProvider orgProvider) {
		if (level == null || pos == null || ownerId == null) {
			return;
		}
		if (!level.getServer().isSameThread()) {
			return;
		}
		WorldRegionData.get(level).onBlockPlaced(level, pos, ownerId, orgProvider);
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
	 * 强制设置半径内区块状态与/或归属。
	 * {@link luowei.player_block_status.lib.api.PlayerBlockStatusLib#forceSetChunks} 与 {@code /pbs set} 均汇入此处。
	 * 详见 {@link WorldRegionData#forceSetChunks}。仅服务端逻辑线程生效。
	 */
	public static int forceSetChunks(
			ServerLevel level,
			ChunkPos center,
			int radiusChunks,
			ChunkState state,
			boolean updateOwner,
			UUID owner
	) {
		if (level == null || center == null) {
			return 0;
		}
		if (!level.getServer().isSameThread()) {
			return 0;
		}
		return WorldRegionData.get(level).forceSetChunks(center, radiusChunks, state, updateOwner, owner);
	}

	/**
	 * 结算触发：每 {@link TerritoryConfig#refreshIntervalTicks} 游戏 tick 一个周期
	 *（{@code gameTime % interval == 0}）。用周期号比较，避免服务器 lag 跳过整除点。
	 */
	public static void tickDaily(ServerLevel level, OrganizationProvider orgProvider, SafeBiomeChecker safeChecker) {
		tickInfection(level);

		long period = currentRefreshPeriod(level);
		WorldRegionData data = WorldRegionData.get(level);
		data.alignLastRefreshPeriod(period);

		if (period <= data.getLastDailyDay()) {
			return;
		}

		TerritoryDailyProcessor.trySchedule(level, orgProvider, safeChecker, period);
	}

	static void tickInfection(ServerLevel level) {
		if (!TerritoryConfig.isInfectionMode() || !Infection.isScheduledTick(level.getGameTime())) {
			return;
		}
		Infection.runForLevel(level);
	}

	/** 调试用：立即跑一遍感染整条链路，不依赖配置模式与 tick。 */
	public static int forceInfection(ServerLevel level) {
		return Infection.runForLevel(level);
	}

	/** 调试用：立即调度一次标脏区块重算，可同一周期重复执行。 */
	public static ScheduleAttempt forceDailyRefresh(
			ServerLevel level,
			OrganizationProvider orgProvider,
			SafeBiomeChecker safeChecker
	) {
		return TerritoryDailyProcessor.tryScheduleForced(level, orgProvider, safeChecker, currentRefreshPeriod(level));
	}

	static long currentRefreshPeriod(ServerLevel level) {
		int interval = Math.max(1, TerritoryConfig.refreshIntervalTicks);
		return level.getGameTime() / interval;
	}
}
