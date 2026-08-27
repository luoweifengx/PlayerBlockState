package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

import net.minecraft.world.level.ChunkPos;

/**
 * 恶魔区块日更扩散：每个恶魔区块先以给定概率进入扩散，再随机挑一个四邻（不含对角）。
 */
public final class DemonChunkSpread {
	private DemonChunkSpread() {
	}

	public static ChunkPos[] cardinalNeighbors(ChunkPos pos) {
		return new ChunkPos[] {
				new ChunkPos(pos.x - 1, pos.z),
				new ChunkPos(pos.x + 1, pos.z),
				new ChunkPos(pos.x, pos.z - 1),
				new ChunkPos(pos.x, pos.z + 1)
		};
	}

	/**
	 * @param enterRoll {@code [0,1)}，小于 {@code probability} 则进入扩散
	 * @param neighborIndex {@code 0..3}，对应 {@link #cardinalNeighbors}
	 */
	public static ChunkPos pickSpreadTarget(ChunkPos origin, double probability, double enterRoll, int neighborIndex) {
		if (enterRoll >= probability) {
			return null;
		}
		ChunkPos[] neighbors = cardinalNeighbors(origin);
		int index = Math.floorMod(neighborIndex, neighbors.length);
		return neighbors[index];
	}

	/**
	 * 对当前恶魔区块集合做一轮日更扩散。返回新转化的邻区键。
	 * 邻区已是恶魔区块时不变。
	 */
	public static List<Long> spreadOnce(
			Set<Long> demonKeys,
			LongPredicate isDemon,
			LongConsumer convertToDemon,
			double probability,
			boolean spreadingEnabled,
			Random random
	) {
		if (!spreadingEnabled || probability <= 0.0d || demonKeys.isEmpty()) {
			return List.of();
		}

		List<Long> created = new ArrayList<>();
		for (long key : List.copyOf(demonKeys)) {
			ChunkPos origin = new ChunkPos(key);
			ChunkPos target = pickSpreadTarget(
					origin,
					probability,
					random.nextDouble(),
					random.nextInt(4)
			);
			if (target == null) {
				continue;
			}
			long neighborKey = target.toLong();
			if (isDemon.test(neighborKey)) {
				continue;
			}
			convertToDemon.accept(neighborKey);
			created.add(neighborKey);
		}
		return created;
	}
}
