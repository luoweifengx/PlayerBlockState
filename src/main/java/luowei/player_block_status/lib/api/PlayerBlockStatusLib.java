package luowei.player_block_status.lib.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.ChunkTerritoryData;
import luowei.player_block_status.lib.chunk.RegionManager;
import luowei.player_block_status.lib.chunk.StructureBounds;
import luowei.player_block_status.lib.chunk.TerritoryDailyProcessor.ScheduleAttempt;
import luowei.player_block_status.lib.chunk.WorldRegionData;
import luowei.player_block_status.lib.debug.ChunkDebugMapRenderer;
import luowei.player_block_status.lib.org.EntityPollList;
import luowei.player_block_status.lib.org.OrganizationService;
import luowei.player_block_status.lib.structure.StructureTerritoryRegistry;

/**
 * 对外公开 API，供其他模组注册回调与查询区块信息。
 */
public final class PlayerBlockStatusLib {
	private static OrganizationProvider organizationProvider = OrganizationProvider.NONE;
	private static SafeBiomeChecker safeBiomeChecker = SafeBiomeChecker.NONE;
	private static final List<EntityPollListListener> ENTITY_POLL_LIST_LISTENERS = new CopyOnWriteArrayList<>();

	private PlayerBlockStatusLib() {
	}

	public static void setOrganizationProvider(OrganizationProvider provider) {
		organizationProvider = provider == null ? OrganizationProvider.NONE : provider;
	}

	public static void setSafeBiomeChecker(SafeBiomeChecker checker) {
		safeBiomeChecker = checker == null ? SafeBiomeChecker.NONE : checker;
	}

	public static OrganizationProvider getOrganizationProvider() {
		return organizationProvider;
	}

	public static SafeBiomeChecker getSafeBiomeChecker() {
		return safeBiomeChecker;
	}

	/**
	 * 注册实体轮询列表变更钩子。若持有指向列表单元的下标，应在回调中根据
	 * {@link EntityPollListChange#mutations()} 更新指针。
	 */
	public static void addEntityPollListListener(EntityPollListListener listener) {
		if (listener != null) {
			ENTITY_POLL_LIST_LISTENERS.add(listener);
		}
	}

	public static void removeEntityPollListListener(EntityPollListListener listener) {
		ENTITY_POLL_LIST_LISTENERS.remove(listener);
	}

	/** 由列表实现调用；消费模组请使用 {@link #addEntityPollListListener}。 */
	public static void notifyEntityPollListListeners(EntityPollListChange change) {
		for (EntityPollListListener listener : ENTITY_POLL_LIST_LISTENERS) {
			listener.onEntityPollListChanged(change);
		}
	}

	/** 实体轮询列表（未入组织的玩家 + 正式组织，按轮询顺序）。 */
	public static EntityPollList getEntityPollList(MinecraftServer server) {
		return OrganizationService.getPollList(server);
	}

	/** 轮询顺序列表快照。 */
	public static List<UUID> getEntityPollOrder(MinecraftServer server) {
		return OrganizationService.getPollList(server).snapshot();
	}

	/** 实体在轮询列表中的下标；不存在返回 -1。 */
	public static int indexOfEntityInPollList(MinecraftServer server, UUID entityId) {
		return OrganizationService.getPollList(server).indexOf(entityId);
	}

	public static void registerStructure(ServerLevel level, StructureBounds bounds) {
		RegionManager.registerStructure(level, bounds);
	}

	public static StructureTerritoryRegistry getStructureTerritoryRegistry() {
		return StructureTerritoryRegistry.INSTANCE;
	}

	public static void transferPlayerToOrg(ServerLevel level, UUID playerId, UUID orgId) {
		RegionManager.transferPlayerToOrg(level, playerId, orgId);
	}

	public static void remapOrganization(ServerLevel level, UUID from, UUID to) {
		RegionManager.remapOrganization(level, from, to);
	}

	public static Optional<ChunkTerritoryData> queryChunk(ServerLevel level, ChunkPos chunkPos) {
		return WorldRegionData.get(level).queryChunk(chunkPos);
	}

	public static Optional<ChunkTerritoryView> queryChunkView(ServerLevel level, ChunkPos chunkPos) {
		return TerritoryQueries.queryChunk(level, chunkPos);
	}

	public static Optional<ChunkTerritoryView> queryChunkAtEntity(ServerLevel level, Entity entity) {
		return TerritoryQueries.queryChunkAtEntity(level, entity);
	}

	public static Optional<ChunkState> queryChunkState(ServerLevel level, ChunkPos chunkPos) {
		return queryChunk(level, chunkPos).map(ChunkTerritoryData::getState);
	}

	/**
	 * 以玩家为中心，切比雪夫半径内按状态分组查询，组内由近到远。
	 */
	public static Map<ChunkState, List<ChunkPos>> queryChunksInRadius(
			ServerLevel level,
			ServerPlayer player,
			int radiusChunks,
			ChunkState... states
	) {
		return TerritoryQueries.queryChunksInRadius(level, player, radiusChunks, states);
	}

	/**
	 * 以区块为中心，切比雪夫半径内按状态分组查询，组内由近到远。
	 */
	public static Map<ChunkState, List<ChunkPos>> queryChunksInRadius(
			ServerLevel level,
			ChunkPos center,
			int radiusChunks,
			ChunkState... states
	) {
		return TerritoryQueries.queryChunksInRadius(level, center, radiusChunks, states);
	}

	/**
	 * 查询组织拥有的 OCCUPIED / BORDER 列表（分表返回）。
	 */
	public static TerritoryQueries.OrgTerritoryChunks queryOrgTerritory(ServerLevel level, UUID orgId) {
		return TerritoryQueries.queryOrgTerritory(level, orgId);
	}

	/**
	 * 查询实体（组织或未入组织玩家）所属区块的平均中心 {@link ChunkPos} 与数量。
	 * 平均值落在相邻区块交界时向上取整；若结果不在所属集合中则吸附到欧氏最近邻所属区块。
	 * 无所属区块返回 empty。
	 */
	public static Optional<TerritoryQueries.TerritoryCentroid> queryTerritoryCentroid(
			ServerLevel level,
			UUID entityId
	) {
		return TerritoryQueries.queryTerritoryCentroid(level, entityId);
	}

	/**
	 * 编码半径内平面状态图字符串（含半径与中心），可用 {@link #decodeChunkStateMap} 解压。
	 */
	public static TerritoryQueries.ChunkStateMapSnapshot encodeChunkStateMap(
			ServerLevel level,
			ChunkPos center,
			int radiusChunks
	) {
		return TerritoryQueries.encodeChunkStateMap(level, center, radiusChunks);
	}

	public static ChunkState[][] decodeChunkStateMap(String encoded) {
		return TerritoryQueries.decodeChunkStateMap(encoded);
	}

	public static Map<Long, ChunkTerritoryData> queryAllChunks(ServerLevel level) {
		return WorldRegionData.get(level).getAllChunks();
	}

	public static Optional<UUID> queryPlayerOrganization(net.minecraft.server.MinecraftServer server, UUID playerId) {
		return luowei.player_block_status.lib.org.OrganizationService.getOrganizationId(server, playerId);
	}

	public static Optional<luowei.player_block_status.lib.org.OrganizationRecord> queryOrganization(
			net.minecraft.server.MinecraftServer server,
			UUID orgId
	) {
		return luowei.player_block_status.lib.org.OrganizationService.getOrganization(server, orgId);
	}

	public static Path exportDebugMap(ServerLevel level, ChunkPos center, int radiusChunks, Path outputPath) {
		return ChunkDebugMapRenderer.render(level, center, radiusChunks, outputPath);
	}

	public static Path exportDebugMap(ServerLevel level, Path outputPath) {
		return ChunkDebugMapRenderer.renderFull(level, outputPath);
	}

	/**
	 * 调试用：在切比雪夫半径内强制设置区块类型与/或所属组织/玩家，并标脏以便后续每日重算纳入。
	 *
	 * @param state        非 null 时写入该状态；null 表示不改状态
	 * @param updateOwner  true 时写入归属；false 表示不改归属
	 * @param owner        归属 UUID（组织或玩家）；{@code updateOwner=true} 且为 null 时清空归属
	 * @return 实际改写的区块数
	 */
	public static int forceSetChunks(
			ServerLevel level,
			ChunkPos center,
			int radiusChunks,
			ChunkState state,
			boolean updateOwner,
			UUID owner
	) {
		return RegionManager.forceSetChunks(level, center, radiusChunks, state, updateOwner, owner);
	}

	/** 仅强制设置区块状态（不改归属）。 */
	public static int forceSetChunkState(ServerLevel level, ChunkPos center, int radiusChunks, ChunkState state) {
		return forceSetChunks(level, center, radiusChunks, state, false, null);
	}

	/** 仅强制设置区块归属（不改状态）；{@code owner == null} 时清空归属。 */
	public static int forceSetChunkOwner(ServerLevel level, ChunkPos center, int radiusChunks, UUID owner) {
		return forceSetChunks(level, center, radiusChunks, null, true, owner);
	}

	/** 调试用：立即调度当前维度一次标脏区块每日重算。 */
	public static ScheduleAttempt forceDailyRefresh(ServerLevel level) {
		return RegionManager.forceDailyRefresh(level, organizationProvider, safeBiomeChecker);
	}
}
