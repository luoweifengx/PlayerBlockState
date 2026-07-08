package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.api.SafeBiomeChecker;

/**
 * 区块状态机：先计算基础状态，再派生边界(3)与敌对边界(4)。
 * 3/4 不会互相转换，边界不扩散，仅基于精确势力范围计算。
 */
public final class ChunkStateMachine {
	private ChunkStateMachine() {
	}

	public static TerritoryDailyProcessor.BaseStateResult computeBaseStateFromSnapshot(
			TerritoryDailyProcessor.DailyChunkSnapshot snapshot
	) {
		ChunkTerritoryData scratch = new ChunkTerritoryData();
		scratch.setState(snapshot.previousState());
		scratch.setOccupyingOrg(snapshot.occupyingOrg());
		scratch.getCachedScores().putAll(snapshot.cachedScores());

		ChunkState next = computeSingleBaseState(scratch, snapshot.previousState(), snapshot.safeChunk());
		return new TerritoryDailyProcessor.BaseStateResult(next, scratch.getOccupyingOrg());
	}

	public static Map<Long, ChunkState> deriveBorderStatesFromBaseStates(Map<Long, ChunkState> baseStates) {
		Map<Long, ChunkState> finalStates = new HashMap<>(baseStates);
		List<Long> borderKeys = new ArrayList<>();

		// ① 先完成全部占领区 → 边界，同时收集边界键
		for (Map.Entry<Long, ChunkState> entry : baseStates.entrySet()) {
			if (entry.getValue() == ChunkState.OCCUPIED && isOccupiedEdge(baseStates, entry.getKey())) {
				long key = entry.getKey();
				finalStates.put(key, ChunkState.BORDER);
				borderKeys.add(key);
			}
		}

		// ② 仅遍历边界区块，向外延伸若干格自然区标为敌对边界
		deriveHostileBordersFromBorderChunks(finalStates, borderKeys);

		return finalStates;
	}

	/**
	 * 以每个 BORDER 为中心，向外延伸 {@link TerritoryConfig#hostileBorderExtensionChunks} 格（切比雪夫距离）内的自然/无记录区块 → HOSTILE_BORDER。
	 */
	private static void deriveHostileBordersFromBorderChunks(Map<Long, ChunkState> states, List<Long> borderKeys) {
		int extension = TerritoryConfig.hostileBorderExtensionChunks;
		for (long borderKey : borderKeys) {
			ChunkPos borderPos = new ChunkPos(borderKey);
			for (int dx = -extension; dx <= extension; dx++) {
				for (int dz = -extension; dz <= extension; dz++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					if (Math.max(Math.abs(dx), Math.abs(dz)) > extension) {
						continue;
					}
					long neighborKey = ChunkPos.asLong(borderPos.x + dx, borderPos.z + dz);
					ChunkState neighborState = states.get(neighborKey);
					if (neighborState == null || neighborState == ChunkState.NATURAL) {
						states.put(neighborKey, ChunkState.HOSTILE_BORDER);
					}
				}
			}
		}
	}

	public static void applyDeathRecoveryToModifiers(ChunkState state, Map<UUID, Integer> scoreModifiers) {
		if (state != ChunkState.DEATH) {
			return;
		}

		for (Map.Entry<UUID, Integer> entry : scoreModifiers.entrySet()) {
			entry.setValue(entry.getValue() + TerritoryConfig.deathRecoveryPerDay);
		}
	}

	public static void computeBaseStates(
			Map<Long, ChunkTerritoryData> chunks,
			Set<Long> affectedKeys,
			ServerLevel level,
			SafeBiomeChecker safeChecker
	) {
		for (long chunkKey : affectedKeys) {
			ChunkTerritoryData chunk = chunks.get(chunkKey);
			if (chunk == null) {
				continue;
			}
			ChunkPos chunkPos = new ChunkPos(chunkKey);
			boolean safeChunk = safeChecker.isSafeChunk(level, chunkPos.getBlockAt(0, level.getMinY(), 0));
			ChunkState next = computeSingleBaseState(chunk, chunk.getState(), safeChunk);
			chunk.setState(next);
		}
	}

	private static ChunkState computeSingleBaseState(
			ChunkTerritoryData chunk,
			ChunkState previous,
			boolean safeChunk
	) {
		if (safeChunk) {
			if (hasDeathScore(chunk)) {
				return ChunkState.DEATH;
			}
			return ChunkState.SAFE;
		}

		if (hasDeathScore(chunk)) {
			return ChunkState.DEATH;
		}

		UUID dominant = findDominantEntity(chunk);
		int maxScore = dominant == null ? 0 : chunk.getCachedScores().getOrDefault(dominant, 0);
		boolean wasOccupiedFamily = previous == ChunkState.OCCUPIED || previous == ChunkState.BORDER;

		if (wasOccupiedFamily) {
			if (allScoresBelowNaturalReturn(chunk)) {
				chunk.setOccupyingOrg(null);
				return ChunkState.NATURAL;
			}
			chunk.setOccupyingOrg(resolveOccupyingOrgWithTakeoverRule(chunk, previous));
			return ChunkState.OCCUPIED;
		}

		if (maxScore >= TerritoryConfig.occupationThreshold) {
			chunk.setOccupyingOrg(dominant);
			return ChunkState.OCCUPIED;
		}

		chunk.setOccupyingOrg(null);
		return ChunkState.NATURAL;
	}

	public static void deriveBorderStates(Map<Long, ChunkTerritoryData> chunks, Set<Long> affectedKeys) {
		Map<Long, ChunkState> baseStates = new HashMap<>();
		for (long key : affectedKeys) {
			ChunkTerritoryData chunk = chunks.get(key);
			if (chunk != null) {
				baseStates.put(key, chunk.getState());
			}
		}

		Map<Long, ChunkState> finalStates = deriveBorderStatesFromBaseStates(baseStates);
		for (Map.Entry<Long, ChunkState> entry : finalStates.entrySet()) {
			ChunkTerritoryData chunk = chunks.get(entry.getKey());
			if (chunk != null) {
				chunk.setState(entry.getValue());
			}
		}
	}

	private static boolean isOccupiedEdge(Map<Long, ChunkState> states, long chunkKey) {
		ChunkPos pos = new ChunkPos(chunkKey);
		for (ChunkPos neighbor : getNeighbors(pos)) {
			ChunkState neighborState = states.get(neighbor.toLong());
			if (neighborState == null || neighborState != ChunkState.OCCUPIED) {
				return true;
			}
		}
		return false;
	}

	private static ChunkPos[] getNeighbors(ChunkPos pos) {
		return new ChunkPos[] {
				new ChunkPos(pos.x - 1, pos.z),
				new ChunkPos(pos.x + 1, pos.z),
				new ChunkPos(pos.x, pos.z - 1),
				new ChunkPos(pos.x, pos.z + 1)
		};
	}

	private static boolean hasDeathScore(ChunkTerritoryData chunk) {
		for (int score : chunk.getCachedScores().values()) {
			if (score <= TerritoryConfig.deathThreshold) {
				return true;
			}
		}
		return false;
	}

	private static boolean allScoresBelowNaturalReturn(ChunkTerritoryData chunk) {
		for (int score : chunk.getCachedScores().values()) {
			if (score >= TerritoryConfig.naturalReturnThreshold) {
				return false;
			}
		}
		return true;
	}

	private static UUID resolveOccupyingOrgWithTakeoverRule(ChunkTerritoryData chunk, ChunkState previous) {
		UUID currentOrg = chunk.getOccupyingOrg();
		if (currentOrg == null) {
			return findDominantEntity(chunk);
		}

		int currentScore = chunk.getCachedScores().getOrDefault(currentOrg, 0);
		double multiplier = previous == ChunkState.BORDER
				? TerritoryConfig.borderTakeoverMultiplier
				: TerritoryConfig.occupationTakeoverMultiplier;
		int requiredScore = (int) Math.ceil(currentScore * multiplier);

		UUID bestChallenger = null;
		int bestChallengerScore = Integer.MIN_VALUE;
		for (Map.Entry<UUID, Integer> entry : chunk.getCachedScores().entrySet()) {
			if (entry.getKey().equals(currentOrg)) {
				continue;
			}
			if (entry.getValue() >= requiredScore && entry.getValue() > bestChallengerScore) {
				bestChallenger = entry.getKey();
				bestChallengerScore = entry.getValue();
			}
		}

		return bestChallenger != null ? bestChallenger : currentOrg;
	}

	private static UUID findDominantEntity(ChunkTerritoryData chunk) {
		UUID dominant = null;
		int maxScore = Integer.MIN_VALUE;
		for (Map.Entry<UUID, Integer> entry : chunk.getCachedScores().entrySet()) {
			if (entry.getValue() > maxScore) {
				maxScore = entry.getValue();
				dominant = entry.getKey();
			}
		}
		return dominant;
	}
}
