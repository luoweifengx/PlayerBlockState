package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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

	/** 未参与本次重算的邻区真实状态 + 归属，供边界/敌对边界判定。 */
	public record NeighborChunkView(ChunkState state, UUID occupyingOrg) {
		public static NeighborChunkView natural() {
			return new NeighborChunkView(ChunkState.NATURAL, null);
		}
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
		return deriveBorderStatesFromBaseStates(baseStates, Map.of(), Map.of());
	}

	/**
	 * @param occupyingOrgs 与 {@code baseStates} 对应的占领归属（OCCUPIED→BORDER 时保留）
	 * @param contextChunks 未参与本次重算的邻区真实状态/归属；须覆盖敌对边界延伸范围
	 */
	public static Map<Long, ChunkState> deriveBorderStatesFromBaseStates(
			Map<Long, ChunkState> baseStates,
			Map<Long, UUID> occupyingOrgs,
			Map<Long, NeighborChunkView> contextChunks
	) {
		Map<Long, ChunkState> finalStates = new HashMap<>(baseStates);
		List<Long> borderKeys = new ArrayList<>();

		// ① 先完成全部占领区 → 边界，同时收集边界键
		for (Map.Entry<Long, ChunkState> entry : baseStates.entrySet()) {
			long key = entry.getKey();
			if (entry.getValue() == ChunkState.OCCUPIED
					&& isOccupiedEdge(baseStates, occupyingOrgs, contextChunks, key, occupyingOrgs.get(key))) {
				finalStates.put(key, ChunkState.BORDER);
				borderKeys.add(key);
			}
		}

		// ② 传播模式：仅遍历边界区块，向外延伸若干格自然区标为敌对边界
		if (TerritoryConfig.isSpreadMode()) {
			deriveHostileBordersFromBorderChunks(finalStates, contextChunks, borderKeys);
		}

		return finalStates;
	}

	/**
	 * 以每个 BORDER 为中心，向外延伸 {@link TerritoryConfig#hostileBorderExtensionChunks} 格（切比雪夫距离）内的自然/无记录区块 → HOSTILE_BORDER。
	 * 仅当邻区真实为 NATURAL 或无领土时标记；禁止覆盖 OCCUPIED/BORDER（含他方）。
	 */
	private static void deriveHostileBordersFromBorderChunks(
			Map<Long, ChunkState> states,
			Map<Long, NeighborChunkView> contextChunks,
			List<Long> borderKeys
	) {
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
					ChunkState neighborState = resolveState(neighborKey, states, contextChunks);
					if (neighborState == ChunkState.DEMON || neighborState == ChunkState.HOSTILE) {
						continue;
					}
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
		if (previous == ChunkState.DEMON) {
			return ChunkState.DEMON;
		}

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
				return naturalReturnState(previous);
			}
			chunk.setOccupyingOrg(resolveOccupyingOrgWithTakeoverRule(chunk, previous));
			return ChunkState.OCCUPIED;
		}

		if (maxScore >= TerritoryConfig.occupationScoreRequired(previous)) {
			chunk.setOccupyingOrg(dominant);
			return ChunkState.OCCUPIED;
		}

		chunk.setOccupyingOrg(null);
		return naturalReturnState(previous);
	}

	/** 原应退回自然时，敌对边界与敌对占领保持不变（不自动收缩）。 */
	private static ChunkState naturalReturnState(ChunkState previous) {
		return previous == ChunkState.HOSTILE_BORDER || previous == ChunkState.HOSTILE
				? previous
				: ChunkState.NATURAL;
	}

	public static void deriveBorderStates(Map<Long, ChunkTerritoryData> chunks, Set<Long> affectedKeys) {
		Map<Long, ChunkState> baseStates = new HashMap<>();
		Map<Long, UUID> occupyingOrgs = new HashMap<>();
		Map<Long, NeighborChunkView> context = new HashMap<>();

		for (long key : affectedKeys) {
			ChunkTerritoryData chunk = chunks.get(key);
			if (chunk != null) {
				baseStates.put(key, chunk.getState());
				occupyingOrgs.put(key, chunk.getOccupyingOrg());
			}
		}

		int extension = TerritoryConfig.hostileBorderExtensionChunks;
		for (long key : affectedKeys) {
			ChunkPos pos = new ChunkPos(key);
			for (int dx = -extension; dx <= extension; dx++) {
				for (int dz = -extension; dz <= extension; dz++) {
					long neighborKey = ChunkPos.asLong(pos.x + dx, pos.z + dz);
					if (baseStates.containsKey(neighborKey) || context.containsKey(neighborKey)) {
						continue;
					}
					ChunkTerritoryData chunk = chunks.get(neighborKey);
					context.put(
							neighborKey,
							chunk == null
									? NeighborChunkView.natural()
									: new NeighborChunkView(chunk.getState(), chunk.getOccupyingOrg())
					);
				}
			}
		}

		Map<Long, ChunkState> finalStates = deriveBorderStatesFromBaseStates(baseStates, occupyingOrgs, context);
		for (Map.Entry<Long, ChunkState> entry : finalStates.entrySet()) {
			ChunkTerritoryData chunk = chunks.get(entry.getKey());
			if (chunk == null) {
				continue;
			}
			ChunkState next = entry.getValue();
			if (next == ChunkState.HOSTILE_BORDER
					&& (chunk.getState() == ChunkState.OCCUPIED
					|| chunk.getState() == ChunkState.BORDER
					|| chunk.getState() == ChunkState.DEMON
					|| chunk.getState() == ChunkState.HOSTILE)) {
				continue;
			}
			if (chunk.getState() == ChunkState.DEMON && next != ChunkState.DEMON) {
				continue;
			}
			chunk.setState(next);
			// BORDER 保留基础态写入的 occupyingOrg；HOSTILE 无归属
			if (next == ChunkState.HOSTILE_BORDER || next == ChunkState.HOSTILE) {
				chunk.setOccupyingOrg(null);
			}
		}
	}

	/**
	 * 占领族边缘：四邻存在无数据/非己方占领族，或邻区为 OCCUPIED/BORDER 但归属不是自己。
	 */
	static boolean isOccupiedEdge(
			Map<Long, ChunkState> baseStates,
			Map<Long, UUID> occupyingOrgs,
			Map<Long, NeighborChunkView> contextChunks,
			long chunkKey,
			UUID selfOrg
	) {
		ChunkPos pos = new ChunkPos(chunkKey);
		for (ChunkPos neighbor : getCardinalNeighbors(pos)) {
			long neighborKey = neighbor.toLong();
			NeighborChunkView neighborView = resolveNeighbor(neighborKey, baseStates, occupyingOrgs, contextChunks);
			if (!isOwnOccupiedFamily(neighborView, selfOrg)) {
				return true;
			}
		}
		return false;
	}

	/** 四邻均为同一 occupyingOrg 的 OCCUPIED/BORDER（邻区无数据则不算）。 */
	public static boolean allCardinalNeighborsOwnOccupiedFamily(
			NeighborResolver resolver,
			long chunkKey,
			UUID selfOrg
	) {
		if (selfOrg == null) {
			return false;
		}
		ChunkPos pos = new ChunkPos(chunkKey);
		for (ChunkPos neighbor : getCardinalNeighbors(pos)) {
			NeighborChunkView view = resolver.resolve(neighbor.toLong());
			if (!isOwnOccupiedFamily(view, selfOrg)) {
				return false;
			}
		}
		return true;
	}

	/** 变化格的四邻（不含自身），供日更只同步一层邻居。 */
	public static Set<Long> cardinalNeighborKeys(Collection<Long> changedKeys) {
		Set<Long> neighbors = new HashSet<>();
		if (changedKeys == null) {
			return neighbors;
		}
		for (long chunkKey : changedKeys) {
			ChunkPos pos = new ChunkPos(chunkKey);
			for (ChunkPos neighbor : getCardinalNeighbors(pos)) {
				neighbors.add(neighbor.toLong());
			}
		}
		return neighbors;
	}

	/** 占领族：四邻都是自己的占领/边界 → OCCUPIED，否则 BORDER。其它状态不变。 */
	public static ChunkState occupiedFamilyFromCardinals(
			NeighborResolver resolver,
			long chunkKey,
			UUID occupyingOrg,
			ChunkState current
	) {
		if (current != ChunkState.OCCUPIED && current != ChunkState.BORDER) {
			return current;
		}
		if (allCardinalNeighborsOwnOccupiedFamily(resolver, chunkKey, occupyingOrg)) {
			return ChunkState.OCCUPIED;
		}
		return ChunkState.BORDER;
	}

	/**
	 * 刚变成 {@link ChunkState#BORDER} / {@link ChunkState#HOSTILE_BORDER} 的格子，只对其四邻做一层降级
	 * （不含对角、不从被降级的邻居再往外传）。
	 * <ul>
	 *   <li>自身 BORDER → 四邻 HOSTILE 变为 HOSTILE_BORDER</li>
	 *   <li>自身 HOSTILE_BORDER → 四邻 OCCUPIED 变为 BORDER（occupyingOrg 由写入路径保留）</li>
	 * </ul>
	 * 不改 DEMON、玩家 OCCUPIED/BORDER（规则 1）、SAFE、DEATH、NATURAL、已是 HOSTILE_BORDER 等。
	 *
	 * @return 需要改写的邻区 → 新状态；不含未变化的格子
	 */
	public static Map<Long, ChunkState> cardinalDemotesFromNewBorders(
			NeighborResolver resolver,
			Collection<Long> seedKeys
	) {
		Map<Long, ChunkState> demotes = new HashMap<>();
		if (resolver == null || seedKeys == null || seedKeys.isEmpty()) {
			return demotes;
		}
		for (long seedKey : seedKeys) {
			NeighborChunkView self = resolver.resolve(seedKey);
			if (self == null) {
				continue;
			}
			ChunkState selfState = self.state();
			if (selfState != ChunkState.BORDER && selfState != ChunkState.HOSTILE_BORDER) {
				continue;
			}
			for (ChunkPos neighbor : getCardinalNeighbors(new ChunkPos(seedKey))) {
				long neighborKey = neighbor.toLong();
				NeighborChunkView view = resolver.resolve(neighborKey);
				if (view == null) {
					continue;
				}
				ChunkState next = cardinalDemoteTarget(selfState, view.state());
				if (next != null) {
					demotes.put(neighborKey, next);
				}
			}
		}
		return demotes;
	}

	/**
	 * @return 邻区应写成的新状态；无需改动时 {@code null}
	 */
	public static ChunkState cardinalDemoteTarget(ChunkState newSelfState, ChunkState neighborState) {
		if (newSelfState == ChunkState.BORDER && neighborState == ChunkState.HOSTILE) {
			return ChunkState.HOSTILE_BORDER;
		}
		if (newSelfState == ChunkState.HOSTILE_BORDER && neighborState == ChunkState.OCCUPIED) {
			return ChunkState.BORDER;
		}
		return null;
	}

	/** 切比雪夫距离 ≤ {@code extension} 内是否存在 BORDER。 */
	public static boolean hasBorderWithinChebyshev(
			NeighborResolver resolver,
			long chunkKey,
			int extension
	) {
		ChunkPos pos = new ChunkPos(chunkKey);
		for (int dx = -extension; dx <= extension; dx++) {
			for (int dz = -extension; dz <= extension; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				if (Math.max(Math.abs(dx), Math.abs(dz)) > extension) {
					continue;
				}
				NeighborChunkView view = resolver.resolve(ChunkPos.asLong(pos.x + dx, pos.z + dz));
				if (view != null && view.state() == ChunkState.BORDER) {
					return true;
				}
			}
		}
		return false;
	}

	@FunctionalInterface
	public interface NeighborResolver {
		/** @return 邻区视图；无 attachment 时返回 {@code null}（不算占领族） */
		NeighborChunkView resolve(long chunkKey);
	}

	static boolean isOwnOccupiedFamily(NeighborChunkView neighbor, UUID selfOrg) {
		if (neighbor == null || selfOrg == null) {
			return false;
		}
		ChunkState state = neighbor.state();
		if (state != ChunkState.OCCUPIED && state != ChunkState.BORDER) {
			return false;
		}
		return selfOrg.equals(neighbor.occupyingOrg());
	}

	private static NeighborChunkView resolveNeighbor(
			long neighborKey,
			Map<Long, ChunkState> baseStates,
			Map<Long, UUID> occupyingOrgs,
			Map<Long, NeighborChunkView> contextChunks
	) {
		if (baseStates.containsKey(neighborKey)) {
			return new NeighborChunkView(baseStates.get(neighborKey), occupyingOrgs.get(neighborKey));
		}
		return contextChunks.get(neighborKey);
	}

	private static ChunkState resolveState(
			long chunkKey,
			Map<Long, ChunkState> workingStates,
			Map<Long, NeighborChunkView> contextChunks
	) {
		if (workingStates.containsKey(chunkKey)) {
			return workingStates.get(chunkKey);
		}
		NeighborChunkView view = contextChunks.get(chunkKey);
		return view == null ? null : view.state();
	}

	private static ChunkPos[] getCardinalNeighbors(ChunkPos pos) {
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
