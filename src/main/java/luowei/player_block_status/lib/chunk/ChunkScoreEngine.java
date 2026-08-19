package luowei.player_block_status.lib.chunk;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import luowei.player_block_status.lib.api.OrganizationProvider;

/**
 * 分数重算：方块分从存储坐标统计，停留分与死亡修正分分别合并。
 */
public final class ChunkScoreEngine {
	private ChunkScoreEngine() {
	}

	public static Map<UUID, Integer> computeTotalScores(
			Map<Long, UUID> placedBlocks,
			Map<UUID, Integer> stayScores,
			Map<UUID, Integer> scoreModifiers,
			OrganizationProvider orgProvider
	) {
		Map<UUID, Integer> blockScores = new HashMap<>();
		for (UUID owner : placedBlocks.values()) {
			if (TerritoryConfig.isStructureSentinel(owner)) {
				continue;
			}
			UUID scoreKey = resolveScoreKey(owner, orgProvider);
			blockScores.merge(scoreKey, TerritoryConfig.blockScorePerBlock, Integer::sum);
		}

		Map<UUID, Integer> totalScores = new HashMap<>(blockScores);
		for (Map.Entry<UUID, Integer> entry : stayScores.entrySet()) {
			UUID scoreKey = resolveScoreKey(entry.getKey(), orgProvider);
			totalScores.merge(scoreKey, entry.getValue(), Integer::sum);
		}
		for (Map.Entry<UUID, Integer> entry : scoreModifiers.entrySet()) {
			UUID scoreKey = resolveScoreKey(entry.getKey(), orgProvider);
			totalScores.merge(scoreKey, entry.getValue(), Integer::sum);
		}
		return totalScores;
	}

	private static UUID resolveScoreKey(UUID entityId, OrganizationProvider orgProvider) {
		return entityId;
	}

	public static UUID resolveEntityId(UUID playerId, OrganizationProvider orgProvider) {
		return playerId;
	}

	public static UUID resolveEntityId(UUID playerId, java.util.function.Function<UUID, java.util.Optional<UUID>> orgLookup) {
		return orgLookup.apply(playerId).orElse(playerId);
	}
}
