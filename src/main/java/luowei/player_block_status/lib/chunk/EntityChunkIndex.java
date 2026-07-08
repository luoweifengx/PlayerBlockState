package luowei.player_block_status.lib.chunk;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 占领组织 → OCCUPIED/BORDER 区块索引，供组织变更与查询使用。
 */
public final class EntityChunkIndex {
	private final Map<UUID, Set<Long>> entityChunks = new HashMap<>();

	public Set<Long> getChunks(UUID entityId) {
		Set<Long> chunks = entityChunks.get(entityId);
		if (chunks == null || chunks.isEmpty()) {
			return Set.of();
		}
		return Collections.unmodifiableSet(new HashSet<>(chunks));
	}

	/**
	 * 玩家加入组织：将该玩家的区块索引合并进组织，并移除玩家条目。
	 */
	public void transferPlayerToOrg(UUID playerId, UUID orgId) {
		Set<Long> playerChunks = entityChunks.remove(playerId);
		if (playerChunks == null || playerChunks.isEmpty()) {
			return;
		}
		entityChunks.computeIfAbsent(orgId, id -> new HashSet<>()).addAll(playerChunks);
	}

	/**
	 * 组织合并：将 from 的区块索引合并进 to，并移除 from 条目。
	 */
	public void mergeOrganization(UUID from, UUID to) {
		Set<Long> fromChunks = entityChunks.remove(from);
		if (fromChunks == null || fromChunks.isEmpty()) {
			return;
		}
		entityChunks.computeIfAbsent(to, id -> new HashSet<>()).addAll(fromChunks);
	}

	public void rebuildOccupiedFrom(Map<Long, ChunkTerritoryData> chunks) {
		entityChunks.clear();
		for (Map.Entry<Long, ChunkTerritoryData> entry : chunks.entrySet()) {
			ChunkTerritoryData chunk = entry.getValue();
			if (!chunk.getState().isOccupiedFamily() || chunk.getOccupyingOrg() == null) {
				continue;
			}
			entityChunks.computeIfAbsent(chunk.getOccupyingOrg(), id -> new HashSet<>()).add(entry.getKey());
		}
	}

	public void clear() {
		entityChunks.clear();
	}
}
