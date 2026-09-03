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
import luowei.player_block_status.lib.compat.ExternalOrganizationBridge;
import luowei.player_block_status.lib.org.CompositeOrganizationProvider;
import luowei.player_block_status.lib.org.EntityDisplayNames;
import luowei.player_block_status.lib.org.EntityPollList;
import luowei.player_block_status.lib.org.OrganizationService;
import luowei.player_block_status.lib.structure.StructureTerritoryRegistry;

/**
 * 对外公开 API：注册回调、通知放置/删分、强制改写区块状态与归属、只读查询区块。
 * <p>
 * 查询一律返回不可变视图，不暴露内部 {@code ChunkTerritoryData}。
 * 导出调试地图、立刻跑感染/日更不在本类；请用 OP 指令 {@code /pbs map}、{@code /pbs infect}、{@code /pbs refresh}。
 * {@code /pbs set} 是 {@link #forceSetChunks} 的 OP 入口。
 */
public final class PlayerBlockStatusLib {
	private static OrganizationProvider organizationProvider = CompositeOrganizationProvider.INSTANCE;
	private static SafeBiomeChecker safeBiomeChecker = SafeBiomeChecker.NONE;
	private static final List<EntityPollListListener> ENTITY_POLL_LIST_LISTENERS = new CopyOnWriteArrayList<>();
	private static final List<BeaconOfferingListener> BEACON_OFFERING_LISTENERS = new CopyOnWriteArrayList<>();

	private PlayerBlockStatusLib() {
	}

	/**
	 * 替换整条账户解析链。若只要加外部层、保留内置回退，请用
	 * {@link ExternalOrganizationBridge#set}，不要在此盖掉组合 Provider。
	 */
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

	/**
	 * 注册信标供奉变更钩子。内置恶魔区块效果也走此链。
	 */
	public static void addBeaconOfferingListener(BeaconOfferingListener listener) {
		if (listener != null) {
			BEACON_OFFERING_LISTENERS.add(listener);
		}
	}

	public static void removeBeaconOfferingListener(BeaconOfferingListener listener) {
		BEACON_OFFERING_LISTENERS.remove(listener);
	}

	/** 由信标追踪调用；消费模组请使用 {@link #addBeaconOfferingListener}。 */
	public static void notifyBeaconOfferingListeners(
			MinecraftServer server,
			BeaconOfferingSnapshot previous,
			BeaconOfferingSnapshot current
	) {
		for (BeaconOfferingListener listener : BEACON_OFFERING_LISTENERS) {
			listener.onBeaconOfferingChanged(server, previous, current);
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

	/**
	 * 通知领土系统：该格已由指定玩家放置，应记入 {@code placed_blocks}（同一格覆盖归属，不叠分）。
	 * <p>
	 * 给绕过原版玩家 {@link net.minecraft.world.item.BlockItem} 放置的外部模组
	 * （例如 {@code level.setBlock}、村民代放、自定义放置逻辑），或需要把放置归属记到某玩家名下。
	 * 玩家用手持方块物品放置时，由内部 mixin 自动通知，无需再调本方法。
	 * 若 mixin 与本方法对同一格都触发，只会覆盖归属，不会叠分。
	 * <p>
	 * {@code ownerId} 是要归属的<strong>玩家 UUID</strong>（离线玩家亦可）。
	 * 若该玩家当前在组织中，内部经 {@link OrganizationProvider} 解析为组织 UUID 再入账。
	 * 若调用方传入的已是组织 UUID，且解析不到对应玩家组织，则原样作为计分账户保留。
	 * <p>
	 * 仅应在服务端逻辑线程调用。内部 mixin 与本方法均汇入
	 * {@link RegionManager#onBlockPlaced}。
	 */
	public static void notifyTrackedBlockPlaced(ServerLevel level, BlockPos pos, UUID ownerId) {
		RegionManager.onBlockPlaced(level, pos, ownerId, getOrganizationProvider());
	}

	/**
	 * 强制改写切比雪夫半径内区块的状态与/或归属（组织或玩家 UUID）。
	 * <p>
	 * 给需要介入领土规则的外部模组：立刻写入当前结果并更新占领索引。
	 * {@code /pbs set} 走同一路径。
	 * <p>
	 * {@code state == null} 表示不改状态；{@code updateOwner == false} 表示不改归属；
	 * {@code updateOwner == true} 时将占领账户设为 {@code owner}（{@code null} 表示清空）。
	 * 半径为切比雪夫距离（正方形），{@code 0} 只改中心一格。尚无领土数据的区块会被创建。
	 * <p>
	 * 写入后会标脏。下一次日更仍按分数重算，强制结果可能被覆盖。
	 * {@link ChunkState#DEMON} 不能被其它状态盖掉；写成恶魔会清掉归属。
	 * <p>
	 * 仅应在服务端逻辑线程调用。非本线程或参数无效时返回 0。
	 *
	 * @return 实际被改写的区块数量
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

	/**
	 * 强制改写单个区块的状态，不改归属（写成 {@link ChunkState#DEMON} 时仍会清归属）。
	 * {@code state == null} 时返回 0。
	 */
	public static int forceSetChunkState(ServerLevel level, ChunkPos pos, ChunkState state) {
		if (state == null) {
			return 0;
		}
		return forceSetChunks(level, pos, 0, state, false, null);
	}

	/**
	 * 强制改写单个区块的归属；{@code owner == null} 表示清空。不改状态。
	 * 当前已是 {@link ChunkState#DEMON} 的区块不会改归属。
	 */
	public static int forceSetChunkOwner(ServerLevel level, ChunkPos pos, UUID owner) {
		return forceSetChunks(level, pos, 0, null, true, owner);
	}

	/** 只读查询已加载区块的领土；未加载或无领土数据时 empty。不触发 chunk 加载。 */
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
	 * 未加载格子视为 {@link ChunkState#NATURAL}，不触发 chunk 加载。
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
	 * 未加载格子视为 {@link ChunkState#NATURAL}，不触发 chunk 加载。
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
	 * 查询实体（组织或未入组织玩家）的占领 / 边界 / 持有区块总数。
	 * 随索引映射表新增或移除区块同步更新。
	 */
	public static TerritoryQueries.EntityTerritoryCounts queryEntityTerritoryCounts(
			ServerLevel level,
			UUID entityId
	) {
		return TerritoryQueries.queryEntityTerritoryCounts(level, entityId);
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

	/** 查询本维度当前全部恶魔区块。 */
	public static List<ChunkPos> queryDemonChunks(ServerLevel level) {
		return TerritoryQueries.queryDemonChunks(level);
	}

	/** 当前全服运作信标数量与最高等级（从主世界 SavedData 推导）。 */
	public static BeaconOfferingSnapshot queryBeaconOffering(MinecraftServer server) {
		return TerritoryQueries.queryBeaconOffering(server);
	}

	/**
	 * 编码半径内平面状态图字符串（含半径与中心），可用 {@link #decodeChunkStateMap} 解压。
	 * 未加载格子编码为 NATURAL，不触发 chunk 加载。
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
		return getOrganizationProvider().getOrganizationId(server, playerId);
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
