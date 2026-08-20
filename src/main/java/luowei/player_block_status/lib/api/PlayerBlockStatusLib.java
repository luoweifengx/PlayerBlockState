package luowei.player_block_status.lib.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.RegionManager;
import luowei.player_block_status.lib.chunk.StructureBounds;
import luowei.player_block_status.lib.org.EntityDisplayNames;
import luowei.player_block_status.lib.org.EntityPollList;
import luowei.player_block_status.lib.org.OrganizationService;
import luowei.player_block_status.lib.structure.StructureTerritoryRegistry;

/**
 * 对外公开 API：注册回调、通知删分、只读查询区块。
 * <p>
 * 查询一律返回不可变视图，不暴露内部 {@code ChunkTerritoryData}。
 * 强制改写区块、导出调试地图不在本类；请用 OP 指令 {@code /pbs set}、{@code /pbs refresh}、{@code /pbs map}。
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

	/**
	 * 通知领土系统：该格已被破坏或移除，应从 {@code placed_blocks} 去掉（幂等）。
	 * <p>
	 * 给绕过原版 {@link net.minecraft.world.level.chunk.LevelChunk#setBlockState} 的外部模组，
	 * 或需要自定义「此格不应再计分」的行为。原版挖掘、爆炸、活塞、流体、火焰、重力等
	 * 只要最终写入 {@code LevelChunk#setBlockState}，由内部 mixin 自动通知，无需再调本方法。
	 * <p>
	 * 仅应在服务端逻辑线程调用。内部 mixin 与本方法均汇入
	 * {@link RegionManager#onBlockRemoved}。
	 */
	public static void notifyTrackedBlockRemoved(ServerLevel level, BlockPos pos) {
		RegionManager.onBlockRemoved(level, pos);
	}

	/** 只读查询区块领土；无领土数据时 empty。 */
	public static Optional<ChunkTerritoryView> queryChunk(ServerLevel level, ChunkPos chunkPos) {
		return TerritoryQueries.queryChunk(level, chunkPos);
	}

	/** {@link #queryChunk} 的别名。 */
	public static Optional<ChunkTerritoryView> queryChunkView(ServerLevel level, ChunkPos chunkPos) {
		return queryChunk(level, chunkPos);
	}

	public static Optional<ChunkTerritoryView> queryChunkAtEntity(ServerLevel level, Entity entity) {
		return TerritoryQueries.queryChunkAtEntity(level, entity);
	}

	public static Optional<ChunkState> queryChunkState(ServerLevel level, ChunkPos chunkPos) {
		return queryChunk(level, chunkPos).map(ChunkTerritoryView::getState);
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

	public static Optional<UUID> queryPlayerOrganization(MinecraftServer server, UUID playerId) {
		return OrganizationService.getOrganizationId(server, playerId);
	}

	public static Optional<luowei.player_block_status.lib.org.OrganizationRecord> queryOrganization(
			MinecraftServer server,
			UUID orgId
	) {
		return OrganizationService.getOrganization(server, orgId);
	}

	/**
	 * 将玩家或组织 UUID 解析为对外显示名。组织用组织名，玩家用持久化名称；
	 * 旧数据无名称时回退到档案缓存或 UUID 字符串。
	 */
	public static String resolveEntityDisplayName(MinecraftServer server, UUID entityId) {
		return EntityDisplayNames.resolve(server, entityId);
	}

	/**
	 * 将玩家或组织 UUID 解析为地区/领地显示名（不是玩家名、不是组织名）。
	 * 已自定义则原样返回；否则为「{实体显示名}的领地」。
	 */
	public static String resolveTerritoryName(MinecraftServer server, UUID entityId) {
		return EntityDisplayNames.resolveTerritoryName(server, entityId);
	}
}
