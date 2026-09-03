package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * 感染模式：抽取全部玩家边界的一半，在切比雪夫 3 范围内先写成敌对边界；
 * 仅当四邻都是敌对（敌对边界 / 敌对占领 / 恶魔）时再升为敌对占领。
 * 核心逻辑不依赖 {@link ServerLevel}，便于单测。
 */
public final class Infection {
	public static final int CHEBYSHEV = 3;
	public static final double SAMPLE_RATE = 0.5;
	public static final int OCCUPIED_SKIP_THRESHOLD = 35;
	public static final int HOSTILE_COUNT_THRESHOLD = 10;
	public static final double STRONGHOLD_PROBABILITY = 0.40;
	public static final double BASE_PROBABILITY = 0.08;

	private Infection() {
	}

	public interface ChunkGrid {
		ChunkState getState(long chunkKey);

		void setState(long chunkKey, ChunkState state);
	}

	public record InfectionResult(
			Set<Long> sampledBorders,
			Set<Long> skippedBorders,
			Set<Long> checkedKeys,
			Map<Long, ChunkState> changed
	) {
		public int changedCount() {
			return changed.size();
		}
	}

	public static boolean isScheduledTick(long gameTime) {
		int interval = Math.max(2, TerritoryConfig.refreshIntervalTicks);
		int half = interval / 2;
		return gameTime % half == 0 && gameTime % interval != 0;
	}

	public static int runForLevel(ServerLevel level) {
		if (level == null) {
			return 0;
		}
		WorldRegionData data = WorldRegionData.get(level);
		Set<Long> borders = data.getEntityChunkIndex().getAllBorderChunks();
		Random random = new Random(level.random.nextLong());
		InfectionResult result = run(worldGrid(data), borders, random);
		PlayerBlockStatus.LOGGER.info(
				"[pbs infect] borders={} sampled={} skipped={} checked={} changed={}",
				borders.size(),
				result.sampledBorders().size(),
				result.skippedBorders().size(),
				result.checkedKeys().size(),
				result.changedCount()
		);
		return result.changedCount();
	}

	public static ChunkGrid worldGrid(WorldRegionData data) {
		return new ChunkGrid() {
			@Override
			public ChunkState getState(long chunkKey) {
				ChunkTerritoryData chunk = data.getChunk(chunkKey);
				return chunk == null ? ChunkState.NATURAL : chunk.getState();
			}

			@Override
			public void setState(long chunkKey, ChunkState state) {
				data.applyDirectState(chunkKey, state);
			}
		};
	}

	public static ChunkGrid mapGrid(Map<Long, ChunkState> states) {
		return new ChunkGrid() {
			@Override
			public ChunkState getState(long chunkKey) {
				return states.getOrDefault(chunkKey, ChunkState.NATURAL);
			}

			@Override
			public void setState(long chunkKey, ChunkState state) {
				states.put(chunkKey, state);
			}
		};
	}

	public static InfectionResult run(ChunkGrid grid, Collection<Long> borderKeys, Random random) {
		Set<Long> sampled = new HashSet<>();
		Set<Long> skipped = new HashSet<>();
		Set<Long> checked = new HashSet<>();
		Map<Long, ChunkState> changed = new HashMap<>();
		if (grid == null || borderKeys == null || borderKeys.isEmpty() || random == null) {
			return new InfectionResult(sampled, skipped, checked, changed);
		}

		List<Long> borders = new ArrayList<>(borderKeys);
		borders.sort(Comparator.naturalOrder());
		List<Long> chosen = new ArrayList<>();
		for (long borderKey : borders) {
			if (random.nextDouble() < SAMPLE_RATE) {
				chosen.add(borderKey);
				sampled.add(borderKey);
			}
		}

		for (long borderKey : chosen) {
			processSampledBorder(grid, borderKey, random, skipped, checked, changed);
		}
		demoteCardinalsAroundNewBorders(grid, changed);
		return new InfectionResult(Set.copyOf(sampled), Set.copyOf(skipped), Set.copyOf(checked), Map.copyOf(changed));
	}

	static boolean shouldSkipSample(int occupiedCount, boolean hasStronghold) {
		return occupiedCount > OCCUPIED_SKIP_THRESHOLD && !hasStronghold;
	}

	static double convertProbability(boolean hasStronghold, int hostileOccupiedCount) {
		if (hasStronghold || hostileOccupiedCount > HOSTILE_COUNT_THRESHOLD) {
			return STRONGHOLD_PROBABILITY;
		}
		return BASE_PROBABILITY;
	}

	static List<Long> chebyshevKeys(long centerKey, int radius) {
		ChunkPos pos = new ChunkPos(centerKey);
		List<Long> keys = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) > radius) {
					continue;
				}
				keys.add(ChunkPos.asLong(pos.x + dx, pos.z + dz));
			}
		}
		return keys;
	}

	static ChunkPos[] cardinalNeighbors(ChunkPos pos) {
		return new ChunkPos[] {
				new ChunkPos(pos.x - 1, pos.z),
				new ChunkPos(pos.x + 1, pos.z),
				new ChunkPos(pos.x, pos.z - 1),
				new ChunkPos(pos.x, pos.z + 1)
		};
	}

	static boolean allCardinalHostile(ChunkGrid grid, long chunkKey) {
		ChunkPos pos = new ChunkPos(chunkKey);
		for (ChunkPos neighbor : cardinalNeighbors(pos)) {
			if (!grid.getState(neighbor.toLong()).isHostileAdjacent()) {
				return false;
			}
		}
		return true;
	}

	private static void processSampledBorder(
			ChunkGrid grid,
			long borderKey,
			Random random,
			Set<Long> skipped,
			Set<Long> checked,
			Map<Long, ChunkState> changed
	) {
		List<Long> range = chebyshevKeys(borderKey, CHEBYSHEV);
		int occupied = 0;
		int hostileOccupied = 0;
		boolean hasDemon = false;
		boolean hasHostile = false;
		for (long key : range) {
			ChunkState state = grid.getState(key);
			if (state == ChunkState.OCCUPIED) {
				occupied++;
			}
			if (state == ChunkState.HOSTILE) {
				hostileOccupied++;
				hasHostile = true;
			}
			if (state == ChunkState.DEMON) {
				hasDemon = true;
			}
		}

		boolean stronghold = hasDemon || hasHostile;
		if (shouldSkipSample(occupied, stronghold)) {
			skipped.add(borderKey);
			return;
		}

		double probability = convertProbability(stronghold, hostileOccupied);
		for (long key : range) {
			if (!checked.add(key)) {
				continue;
			}
			convertCell(grid, key, probability, random, changed);
		}
	}

	private static void convertCell(
			ChunkGrid grid,
			long key,
			double probability,
			Random random,
			Map<Long, ChunkState> changed
	) {
		ChunkState current = grid.getState(key);
		if (isImmutable(current)) {
			return;
		}
		if (current == ChunkState.HOSTILE_BORDER) {
			applyAdjacency(grid, key, changed);
			return;
		}
		if (current != ChunkState.NATURAL) {
			return;
		}
		if (random.nextDouble() >= probability) {
			return;
		}
		grid.setState(key, ChunkState.HOSTILE_BORDER);
		changed.put(key, ChunkState.HOSTILE_BORDER);
		applyAdjacency(grid, key, changed);
	}

	static void demoteCardinalsAroundNewBorders(ChunkGrid grid, Map<Long, ChunkState> changed) {
		if (grid == null || changed == null || changed.isEmpty()) {
			return;
		}
		Set<Long> seeds = new HashSet<>();
		for (long key : changed.keySet()) {
			ChunkState state = grid.getState(key);
			if (state == ChunkState.BORDER || state == ChunkState.HOSTILE_BORDER) {
				seeds.add(key);
			}
		}
		ChunkStateMachine.NeighborResolver resolver = chunkKey ->
				new ChunkStateMachine.NeighborChunkView(grid.getState(chunkKey), null);
		Map<Long, ChunkState> demotes = ChunkStateMachine.cardinalDemotesFromNewBorders(resolver, seeds);
		for (Map.Entry<Long, ChunkState> entry : demotes.entrySet()) {
			grid.setState(entry.getKey(), entry.getValue());
			changed.put(entry.getKey(), entry.getValue());
		}
	}

	static void applyAdjacency(ChunkGrid grid, long key, Map<Long, ChunkState> changed) {
		ChunkState self = grid.getState(key);
		if (canPromoteToHostileOccupied(self) && self != ChunkState.HOSTILE && allCardinalHostile(grid, key)) {
			grid.setState(key, ChunkState.HOSTILE);
			changed.put(key, ChunkState.HOSTILE);
		}
		ChunkPos pos = new ChunkPos(key);
		for (ChunkPos neighbor : cardinalNeighbors(pos)) {
			long neighborKey = neighbor.toLong();
			if (grid.getState(neighborKey) != ChunkState.HOSTILE_BORDER) {
				continue;
			}
			if (allCardinalHostile(grid, neighborKey)) {
				grid.setState(neighborKey, ChunkState.HOSTILE);
				changed.put(neighborKey, ChunkState.HOSTILE);
			}
		}
	}

	private static boolean canPromoteToHostileOccupied(ChunkState state) {
		return state == ChunkState.HOSTILE_BORDER || state == ChunkState.NATURAL || state == ChunkState.HOSTILE;
	}

	private static boolean isImmutable(ChunkState state) {
		return state == ChunkState.SAFE
				|| state == ChunkState.DEMON
				|| state == ChunkState.OCCUPIED
				|| state == ChunkState.BORDER
				|| state == ChunkState.DEATH
				|| state == ChunkState.HOSTILE;
	}
}
