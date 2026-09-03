package luowei.player_block_status.lib.chunk;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * 占领组织 → OCCUPIED / BORDER 分表索引。数据层分开，调用方按需融合。
 * 随维度 SavedData 落盘；仍以各 chunk 的 {@code state}/{@code occupyingOrg} 为真相。
 * <p>
 * 每个实体另维护占领/边界/持有计数：映射表真正新增或移除 chunk key 时同步更新，
 * 查询计数不必拷贝或合并整张集合。
 */
public final class EntityChunkIndex {
	private final Map<UUID, Set<Long>> occupiedChunks = new HashMap<>();
	private final Map<UUID, Set<Long>> borderChunks = new HashMap<>();
	private final Map<UUID, Integer> occupiedCounts = new HashMap<>();
	private final Map<UUID, Integer> borderCounts = new HashMap<>();
	private final Runnable onDirty;

	public EntityChunkIndex() {
		this(null);
	}

	public EntityChunkIndex(Runnable onDirty) {
		this.onDirty = onDirty == null ? () -> {
		} : onDirty;
	}

	public void load(Map<UUID, Set<Long>> occupied, Map<UUID, Set<Long>> border) {
		occupiedChunks.clear();
		borderChunks.clear();
		copyInto(occupied, occupiedChunks);
		copyInto(border, borderChunks);
		recountAll();
	}

	public Map<UUID, Set<Long>> copyOccupiedForPersist() {
		return copyOut(occupiedChunks);
	}

	public Map<UUID, Set<Long>> copyBorderForPersist() {
		return copyOut(borderChunks);
	}

	public Set<Long> getOccupiedChunks(UUID entityId) {
		return copyOrEmpty(occupiedChunks.get(entityId));
	}

	public Set<Long> getBorderChunks(UUID entityId) {
		return copyOrEmpty(borderChunks.get(entityId));
	}

	/** 本维度全部实体的 BORDER 区块并集（去重）。 */
	public Set<Long> getAllBorderChunks() {
		Set<Long> all = new HashSet<>();
		for (Set<Long> keys : borderChunks.values()) {
			if (keys != null && !keys.isEmpty()) {
				all.addAll(keys);
			}
		}
		if (all.isEmpty()) {
			return Set.of();
		}
		return Collections.unmodifiableSet(all);
	}

	/** 组织变更等内部路径：OCCUPIED ∪ BORDER。 */
	public Set<Long> getOwnedChunks(UUID entityId) {
		Set<Long> occupied = occupiedChunks.get(entityId);
		Set<Long> border = borderChunks.get(entityId);
		if ((occupied == null || occupied.isEmpty()) && (border == null || border.isEmpty())) {
			return Set.of();
		}
		Set<Long> merged = new HashSet<>();
		if (occupied != null) {
			merged.addAll(occupied);
		}
		if (border != null) {
			merged.addAll(border);
		}
		return Collections.unmodifiableSet(merged);
	}

	/** 该实体 OCCUPIED 区块数；无条目为 0。 */
	public int getOccupiedCount(UUID entityId) {
		return occupiedCounts.getOrDefault(entityId, 0);
	}

	/** 该实体 BORDER 区块数；无条目为 0。 */
	public int getBorderCount(UUID entityId) {
		return borderCounts.getOrDefault(entityId, 0);
	}

	/** 该实体持有区块数（OCCUPIED + BORDER）。两表互斥，无需去重。 */
	public int getOwnedCount(UUID entityId) {
		return getOccupiedCount(entityId) + getBorderCount(entityId);
	}

	/**
	 * 玩家加入组织：将该玩家的 OCCUPIED/BORDER 索引分别合并进组织，并移除玩家条目。
	 */
	public void transferPlayerToOrg(UUID playerId, UUID orgId) {
		transferMap(occupiedChunks, occupiedCounts, playerId, orgId);
		transferMap(borderChunks, borderCounts, playerId, orgId);
		onDirty.run();
	}

	/**
	 * 组织合并：将 from 的索引分别合并进 to，并移除 from 条目。
	 */
	public void mergeOrganization(UUID from, UUID to) {
		transferMap(occupiedChunks, occupiedCounts, from, to);
		transferMap(borderChunks, borderCounts, from, to);
		onDirty.run();
	}

	/**
	 * 按单块最新状态更新倒排索引，避免为重建而加载全部 active。
	 * 映射表新增/移除 key 时同步维护计数。
	 * <p>
	 * 先从 occupied/border 两表都删掉该 key，再仅当 {@code state == OCCUPIED/BORDER}
	 * 且 {@code occupyingOrg != null} 时写回。因此 NATURAL / DEMON / HOSTILE_BORDER
	 * 或 {@code occupyingOrg == null} 会把该 key 从倒排索引抹掉。
	 * 若 key 原先在某张表里、写回后不在该表，打 warn 并附调用栈（查 {@code untrackActiveChunk} 等误清）。
	 */
	public void replaceChunk(long chunkKey, ChunkState state, UUID occupyingOrg) {
		UUID prevOccupiedOrg = removeChunkKey(occupiedChunks, occupiedCounts, chunkKey);
		UUID prevBorderOrg = removeChunkKey(borderChunks, borderCounts, chunkKey);
		if (occupyingOrg != null) {
			if (state == ChunkState.OCCUPIED) {
				addChunk(occupiedChunks, occupiedCounts, occupyingOrg, chunkKey);
			} else if (state == ChunkState.BORDER) {
				addChunk(borderChunks, borderCounts, occupyingOrg, chunkKey);
			}
		}
		boolean staysOccupied = occupyingOrg != null && state == ChunkState.OCCUPIED;
		boolean staysBorder = occupyingOrg != null && state == ChunkState.BORDER;
		boolean droppedOccupied = prevOccupiedOrg != null && !staysOccupied;
		boolean droppedBorder = prevBorderOrg != null && !staysBorder;
		if (droppedOccupied || droppedBorder) {
			logMembershipDrop(chunkKey, prevOccupiedOrg, prevBorderOrg, state, occupyingOrg);
		}
		onDirty.run();
	}

	public void rebuildFrom(Map<Long, ChunkTerritoryData> chunks) {
		logIfWipingTables("rebuildFrom");
		occupiedChunks.clear();
		borderChunks.clear();
		occupiedCounts.clear();
		borderCounts.clear();
		for (Map.Entry<Long, ChunkTerritoryData> entry : chunks.entrySet()) {
			ChunkTerritoryData chunk = entry.getValue();
			UUID org = chunk.getOccupyingOrg();
			if (org == null) {
				continue;
			}
			if (chunk.getState() == ChunkState.OCCUPIED) {
				addChunk(occupiedChunks, occupiedCounts, org, entry.getKey());
			} else if (chunk.getState() == ChunkState.BORDER) {
				addChunk(borderChunks, borderCounts, org, entry.getKey());
			}
		}
		onDirty.run();
	}

	/** @deprecated 使用 {@link #rebuildFrom(Map)} */
	@Deprecated
	public void rebuildOccupiedFrom(Map<Long, ChunkTerritoryData> chunks) {
		rebuildFrom(chunks);
	}

	public void clear() {
		logIfWipingTables("clear");
		occupiedChunks.clear();
		borderChunks.clear();
		occupiedCounts.clear();
		borderCounts.clear();
		onDirty.run();
	}

	private void recountAll() {
		occupiedCounts.clear();
		borderCounts.clear();
		fillCounts(occupiedChunks, occupiedCounts);
		fillCounts(borderChunks, borderCounts);
	}

	private static void fillCounts(Map<UUID, Set<Long>> chunks, Map<UUID, Integer> counts) {
		chunks.forEach((id, keys) -> {
			if (keys != null && !keys.isEmpty()) {
				counts.put(id, keys.size());
			}
		});
	}

	private static void copyInto(Map<UUID, Set<Long>> source, Map<UUID, Set<Long>> target) {
		if (source == null) {
			return;
		}
		source.forEach((id, keys) -> {
			if (id == null || keys == null || keys.isEmpty()) {
				return;
			}
			target.put(id, new HashSet<>(keys));
		});
	}

	private static Map<UUID, Set<Long>> copyOut(Map<UUID, Set<Long>> source) {
		Map<UUID, Set<Long>> copy = new HashMap<>();
		source.forEach((id, keys) -> {
			if (keys != null && !keys.isEmpty()) {
				copy.put(id, Set.copyOf(keys));
			}
		});
		return copy;
	}

	private static void addChunk(
			Map<UUID, Set<Long>> map,
			Map<UUID, Integer> counts,
			UUID entityId,
			long chunkKey
	) {
		if (map.computeIfAbsent(entityId, id -> new HashSet<>()).add(chunkKey)) {
			counts.merge(entityId, 1, Integer::sum);
		}
	}

	/**
	 * 从该表移除 {@code chunkKey}。返回原先持有该 key 的实体；未命中为 {@code null}。
	 */
	private static UUID removeChunkKey(Map<UUID, Set<Long>> map, Map<UUID, Integer> counts, long chunkKey) {
		UUID[] owner = new UUID[1];
		map.entrySet().removeIf(entry -> {
			if (!entry.getValue().remove(chunkKey)) {
				return false;
			}
			owner[0] = entry.getKey();
			decrementCount(counts, entry.getKey());
			return entry.getValue().isEmpty();
		});
		return owner[0];
	}

	/**
	 * key 曾在 occupied 或 border 表中，本次 replace 后不再写回该表时记录。
	 * 含调用栈，便于对照 {@code untrackActiveChunk} / 日更 / 感染。
	 */
	private static void logMembershipDrop(
			long chunkKey,
			UUID prevOccupiedOrg,
			UUID prevBorderOrg,
			ChunkState state,
			UUID occupyingOrg
	) {
		int cx = (int) chunkKey;
		int cz = (int) (chunkKey >> 32);
		PlayerBlockStatus.LOGGER.warn(
				"[pbs index] replaceChunk dropped index membership chunkKey={} cx={} cz={} wasOccupied={} wasBorder={} prevOccupiedOrg={} prevBorderOrg={} newState={} newOccupyingOrg={}",
				chunkKey,
				cx,
				cz,
				prevOccupiedOrg != null,
				prevBorderOrg != null,
				prevOccupiedOrg,
				prevBorderOrg,
				state,
				occupyingOrg,
				new Throwable("replaceChunk index drop")
		);
	}

	private void logIfWipingTables(String op) {
		if (occupiedChunks.isEmpty() && borderChunks.isEmpty()) {
			return;
		}
		PlayerBlockStatus.LOGGER.warn(
				"[pbs index] {} wiping occupiedOrgs={} borderOrgs={}",
				op,
				occupiedChunks.size(),
				borderChunks.size(),
				new Throwable(op + " index wipe")
		);
	}

	private static void transferMap(
			Map<UUID, Set<Long>> map,
			Map<UUID, Integer> counts,
			UUID from,
			UUID to
	) {
		Set<Long> fromChunks = map.remove(from);
		counts.remove(from);
		if (fromChunks == null || fromChunks.isEmpty()) {
			return;
		}
		Set<Long> toChunks = map.computeIfAbsent(to, id -> new HashSet<>());
		int added = 0;
		for (long key : fromChunks) {
			if (toChunks.add(key)) {
				added++;
			}
		}
		if (added > 0) {
			counts.merge(to, added, Integer::sum);
		}
		if (toChunks.isEmpty()) {
			map.remove(to);
			counts.remove(to);
		}
	}

	private static void decrementCount(Map<UUID, Integer> counts, UUID entityId) {
		Integer current = counts.get(entityId);
		if (current == null) {
			return;
		}
		if (current <= 1) {
			counts.remove(entityId);
		} else {
			counts.put(entityId, current - 1);
		}
	}

	private static Set<Long> copyOrEmpty(Set<Long> chunks) {
		if (chunks == null || chunks.isEmpty()) {
			return Set.of();
		}
		return Collections.unmodifiableSet(new HashSet<>(chunks));
	}
}
