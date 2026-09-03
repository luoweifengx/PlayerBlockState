package luowei.player_block_status.lib.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.DemonChunkWorldData;
import luowei.player_block_status.lib.chunk.WorldRegionData;

/**
 * 区块领土查询服务。
 */
public final class TerritoryQueries {
	private static final String MAP_PREFIX = "PBS1";

	private TerritoryQueries() {
	}

	/**
	 * 只读查询已加载区块的领土；未加载或不含领土数据时 empty。
	 * 不触发 chunk 加载或生成。
	 */
	public static Optional<ChunkTerritoryView> queryChunk(ServerLevel level, ChunkPos chunkPos) {
		return WorldRegionData.get(level).queryChunk(chunkPos)
				.map(data -> ChunkTerritoryView.from(chunkPos, data));
	}

	public static Optional<ChunkTerritoryView> queryChunkAtEntity(ServerLevel level, Entity entity) {
		return queryChunk(level, new ChunkPos(entity.blockPosition()));
	}

	/**
	 * 以玩家为中心，在切比雪夫半径内查询指定状态的区块，按状态分组，组内由近到远排序。
	 */
	public static Map<ChunkState, List<ChunkPos>> queryChunksInRadius(
			ServerLevel level,
			ServerPlayer player,
			int radiusChunks,
			ChunkState... states
	) {
		return queryChunksInRadius(level, player.chunkPosition(), radiusChunks, states);
	}

	/**
	 * 以区块为中心，在切比雪夫半径内查询指定状态的区块，按状态分组，组内由近到远排序。
	 * 未加载格子视为 {@link ChunkState#NATURAL}，不触发 chunk 加载。
	 */
	public static Map<ChunkState, List<ChunkPos>> queryChunksInRadius(
			ServerLevel level,
			ChunkPos center,
			int radiusChunks,
			ChunkState... states
	) {
		if (radiusChunks < 0 || states.length == 0) {
			return Map.of();
		}

		Set<ChunkState> wanted = EnumSet.copyOf(Arrays.asList(states));
		Map<ChunkState, List<ChunkPos>> buckets = new EnumMap<>(ChunkState.class);
		for (ChunkState state : wanted) {
			buckets.put(state, new ArrayList<>());
		}

		for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
			for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) > radiusChunks) {
					continue;
				}
				ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
				ChunkState state = resolveState(level, pos);
				List<ChunkPos> list = buckets.get(state);
				if (list != null) {
					list.add(pos);
				}
			}
		}

		Comparator<ChunkPos> byDistance = Comparator
				.comparingInt((ChunkPos pos) -> chebyshev(center, pos))
				.thenComparingInt(pos -> pos.x)
				.thenComparingInt(pos -> pos.z);
		for (List<ChunkPos> list : buckets.values()) {
			list.sort(byDistance);
		}
		return buckets;
	}

	/**
	 * 按组织返回 OCCUPIED / BORDER 两个列表（数据层分表，此处分别拷贝）。
	 */
	public static OrgTerritoryChunks queryOrgTerritory(ServerLevel level, UUID orgId) {
		var index = WorldRegionData.get(level).getEntityChunkIndex();
		return new OrgTerritoryChunks(
				toChunkPosList(index.getOccupiedChunks(orgId)),
				toChunkPosList(index.getBorderChunks(orgId))
		);
	}

	/**
	 * 查询实体（组织或未入组织玩家）当前持有的占领 / 边界 / 合计区块数。
	 * 计数随倒排索引增删同步维护，不拷贝区块集合。
	 */
	public static EntityTerritoryCounts queryEntityTerritoryCounts(ServerLevel level, UUID entityId) {
		var index = WorldRegionData.get(level).getEntityChunkIndex();
		return new EntityTerritoryCounts(
				index.getOccupiedCount(entityId),
				index.getBorderCount(entityId),
				index.getOwnedCount(entityId)
		);
	}

	/**
	 * 根据实体（组织或未入组织玩家）所属 OCCUPIED ∪ BORDER 计算平均中心区块。
	 * <p>
	 * 对所有所属 {@link ChunkPos} 的 x/z 分别求和再除以数量；若平均值落在相邻区块交界
	 *（非整数）则向上取整（朝 +∞），整数则保持该区块。若该点本身不是所属区块，再在所属集合中
	 * 做欧氏距离最近邻（并列时取 x 更小，再取 z 更小）。无所属区块时返回 empty。
	 */
	public static Optional<TerritoryCentroid> queryTerritoryCentroid(ServerLevel level, UUID entityId) {
		Set<Long> owned = WorldRegionData.get(level).getEntityChunkIndex().getOwnedChunks(entityId);
		if (owned.isEmpty()) {
			return Optional.empty();
		}
		long sumX = 0;
		long sumZ = 0;
		for (long key : owned) {
			ChunkPos pos = new ChunkPos(key);
			sumX += pos.x;
			sumZ += pos.z;
		}
		int count = owned.size();
		ChunkPos average = new ChunkPos(ceilAvgToChunkCoord(sumX, count), ceilAvgToChunkCoord(sumZ, count));
		ChunkPos center = nearestOwnedChunk(average, owned);
		return Optional.of(new TerritoryCentroid(center, count));
	}

	/** 本维度当前恶魔区块列表，按 x 再 z 排序。 */
	public static List<ChunkPos> queryDemonChunks(ServerLevel level) {
		return toChunkPosList(WorldRegionData.get(level).getDemonChunkKeys());
	}

	/** 全服运作信标快照（主世界 SavedData）。 */
	public static BeaconOfferingSnapshot queryBeaconOffering(MinecraftServer server) {
		return DemonChunkWorldData.get(server).snapshot();
	}

	/**
	 * 以中心与切比雪夫半径编码平面状态图。
	 * 格式：{@code PBS1:<radius>:<centerX>:<centerZ>:<data>}，
	 * {@code data} 长度 {@code (2r+1)^2}，行优先（dz 外、dx 内），每字符为状态 id（'1'..'8'）。
	 * 未加载格子编码为 NATURAL，不触发 chunk 加载。
	 */
	public static ChunkStateMapSnapshot encodeChunkStateMap(ServerLevel level, ChunkPos center, int radiusChunks) {
		if (radiusChunks < 0) {
			throw new IllegalArgumentException("radiusChunks must be >= 0");
		}
		int side = radiusChunks * 2 + 1;
		StringBuilder data = new StringBuilder(side * side);
		for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
			for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
				ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
				data.append((char) ('0' + resolveState(level, pos).getId()));
			}
		}
		String encoded = MAP_PREFIX + ':' + radiusChunks + ':' + center.x + ':' + center.z + ':' + data;
		return new ChunkStateMapSnapshot(encoded, radiusChunks, center);
	}

	/**
	 * 解码 {@link #encodeChunkStateMap} 的字符串为平面状态矩阵，下标 {@code [dz+r][dx+r]}。
	 */
	public static ChunkState[][] decodeChunkStateMap(String encoded) {
		String[] parts = encoded.split(":", 5);
		if (parts.length != 5 || !MAP_PREFIX.equals(parts[0])) {
			throw new IllegalArgumentException("Invalid chunk state map encoding");
		}
		int radius = Integer.parseInt(parts[1]);
		int side = radius * 2 + 1;
		String data = parts[4];
		if (data.length() != side * side) {
			throw new IllegalArgumentException(
					"Chunk state map data length mismatch: expected " + (side * side) + ", got " + data.length()
			);
		}
		ChunkState[][] grid = new ChunkState[side][side];
		int i = 0;
		for (int row = 0; row < side; row++) {
			for (int col = 0; col < side; col++) {
				grid[row][col] = ChunkState.fromId(data.charAt(i++) - '0');
			}
		}
		return grid;
	}

	/** 实体领土规模：占领、边界与持有合计（OCCUPIED + BORDER）。 */
	public record EntityTerritoryCounts(int occupied, int border, int owned) {
	}

	public record OrgTerritoryChunks(List<ChunkPos> occupied, List<ChunkPos> border) {
		public OrgTerritoryChunks {
			occupied = List.copyOf(occupied);
			border = List.copyOf(border);
		}

		public List<ChunkPos> allOwned() {
			List<ChunkPos> all = new ArrayList<>(occupied.size() + border.size());
			all.addAll(occupied);
			all.addAll(border);
			return List.copyOf(all);
		}
	}

	/** 领土质心：平均中心区块坐标与所属区块数量。 */
	public record TerritoryCentroid(ChunkPos center, int chunkCount) {
	}

	public record ChunkStateMapSnapshot(String encoded, int radius, ChunkPos center) {
	}

	/** 已加载且有领土数据则取其状态，否则 NATURAL（含未加载）。 */
	private static ChunkState resolveState(ServerLevel level, ChunkPos chunkPos) {
		return WorldRegionData.get(level).queryChunk(chunkPos)
				.map(data -> data.getState())
				.orElse(ChunkState.NATURAL);
	}

	private static int chebyshev(ChunkPos a, ChunkPos b) {
		return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
	}

	/**
	 * 精确整数除法向上取整：{@code ceil(sum / count)}，落在相邻区块交界时取较大侧。
	 */
	private static int ceilAvgToChunkCoord(long sum, int count) {
		if (sum >= 0) {
			return (int) ((sum + count - 1L) / count);
		}
		// 负数：Java 向零截断等价于朝 +∞ 的 ceil（有余数时）
		return (int) (sum / count);
	}

	/** 将平均点吸附到所属集合中的最近区块；已在集合内则直接返回。 */
	private static ChunkPos nearestOwnedChunk(ChunkPos average, Set<Long> owned) {
		long averageKey = average.toLong();
		if (owned.contains(averageKey)) {
			return average;
		}
		ChunkPos best = null;
		long bestDistSq = Long.MAX_VALUE;
		for (long key : owned) {
			ChunkPos pos = new ChunkPos(key);
			long dx = (long) pos.x - average.x;
			long dz = (long) pos.z - average.z;
			long distSq = dx * dx + dz * dz;
			if (best == null
					|| distSq < bestDistSq
					|| (distSq == bestDistSq && (pos.x < best.x || (pos.x == best.x && pos.z < best.z)))) {
				best = pos;
				bestDistSq = distSq;
			}
		}
		return best;
	}

	private static List<ChunkPos> toChunkPosList(Set<Long> keys) {
		List<ChunkPos> list = new ArrayList<>(keys.size());
		for (long key : keys) {
			list.add(new ChunkPos(key));
		}
		list.sort(Comparator.comparingInt((ChunkPos p) -> p.x).thenComparingInt(p -> p.z));
		return list;
	}
}
