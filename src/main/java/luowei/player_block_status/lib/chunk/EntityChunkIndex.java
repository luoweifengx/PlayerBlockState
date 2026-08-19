package luowei.player_block_status.lib.chunk;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 占领组织 → OCCUPIED / BORDER 分表索引。数据层分开，调用方按需融合。
 * 随维度 SavedData 落盘；仍以各 chunk 的 {@code state}/{@code occupyingOrg} 为真相。
 */
public final class EntityChunkIndex {
	private final Map<UUID, Set<Long>> occupiedChunks = new HashMap<>();
	private final Map<UUID, Set<Long>> borderChunks = new HashMap<>();
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

	/**
	 * 玩家加入组织：将该玩家的 OCCUPIED/BORDER 索引分别合并进组织，并移除玩家条目。
	 */
	public void transferPlayerToOrg(UUID playerId, UUID orgId) {
		transferMap(occupiedChunks, playerId, orgId);
		transferMap(borderChunks, playerId, orgId);
		onDirty.run();
	}

	/**
	 * 组织合并：将 from 的索引分别合并进 to，并移除 from 条目。
	 */
	public void mergeOrganization(UUID from, UUID to) {
		transferMap(occupiedChunks, from, to);
		transferMap(borderChunks, from, to);
		onDirty.run();
	}

	/**
	 * 按单块最新状态更新倒排索引，避免为重建而加载全部 active。
	 */
	public void replaceChunk(long chunkKey, ChunkState state, UUID occupyingOrg) {
		removeChunkKey(occupiedChunks, chunkKey);
		removeChunkKey(borderChunks, chunkKey);
		if (occupyingOrg != null) {
			if (state == ChunkState.OCCUPIED) {
				occupiedChunks.computeIfAbsent(occupyingOrg, id -> new HashSet<>()).add(chunkKey);
			} else if (state == ChunkState.BORDER) {
				borderChunks.computeIfAbsent(occupyingOrg, id -> new HashSet<>()).add(chunkKey);
			}
		}
		onDirty.run();
	}

	public void rebuildFrom(Map<Long, ChunkTerritoryData> chunks) {
		occupiedChunks.clear();
		borderChunks.clear();
		for (Map.Entry<Long, ChunkTerritoryData> entry : chunks.entrySet()) {
			ChunkTerritoryData chunk = entry.getValue();
			UUID org = chunk.getOccupyingOrg();
			if (org == null) {
				continue;
			}
			if (chunk.getState() == ChunkState.OCCUPIED) {
				occupiedChunks.computeIfAbsent(org, id -> new HashSet<>()).add(entry.getKey());
			} else if (chunk.getState() == ChunkState.BORDER) {
				borderChunks.computeIfAbsent(org, id -> new HashSet<>()).add(entry.getKey());
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
		occupiedChunks.clear();
		borderChunks.clear();
		onDirty.run();
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

	private static void removeChunkKey(Map<UUID, Set<Long>> map, long chunkKey) {
		map.values().removeIf(set -> {
			set.remove(chunkKey);
			return set.isEmpty();
		});
	}

	private static void transferMap(Map<UUID, Set<Long>> map, UUID from, UUID to) {
		Set<Long> fromChunks = map.remove(from);
		if (fromChunks == null || fromChunks.isEmpty()) {
			return;
		}
		map.computeIfAbsent(to, id -> new HashSet<>()).addAll(fromChunks);
	}

	private static Set<Long> copyOrEmpty(Set<Long> chunks) {
		if (chunks == null || chunks.isEmpty()) {
			return Set.of();
		}
		return Collections.unmodifiableSet(new HashSet<>(chunks));
	}
}
