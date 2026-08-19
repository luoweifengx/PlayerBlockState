package luowei.player_block_status.lib.chunk;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 领土系统阈值与加分常量，可由消费模组在启动时覆盖。
 */
public final class TerritoryConfig {
	/**
	 * 结构生成时写入 {@code placed_blocks} 的占位归属（未认领结构方块）。
	 * 玩家放置后仅沿六邻 sentinel 链式改写为玩家/组织 UUID，不会把自然地形认领进来。
	 */
	public static final UUID STRUCTURE_BLOCK_SENTINEL = UUID.nameUUIDFromBytes(
			"player-block-status:structure-block-sentinel".getBytes(StandardCharsets.UTF_8)
	);

	/** 进入占领状态所需分数 */
	public static int occupationThreshold = 1000;
	/** 从占领/边界退回自然状态：所有玩家分数均低于此值 */
	public static int naturalReturnThreshold = 500;
	public static int deathThreshold = -100;
	/** 每放置方块得分（调试临时值；原始值：4） */
	public static int blockScorePerBlock = 300;
	public static int stayScorePerInterval = 3;
	public static int stayTickInterval = 100;
	public static int deathPenalty = -50;
	public static int deathRecoveryPerDay = 30;
	/** 从占领区块夺走所属：挑战者分数须 ≥ 当前所属分数 × 此倍率 */
	public static double occupationTakeoverMultiplier = 2.5;
	/** 从边界区块夺走所属：挑战者分数须 ≥ 当前所属分数 × 此倍率 */
	public static double borderTakeoverMultiplier = 1.25;
	/** 从边界区块向外延伸的自然区块层数（切比雪夫距离），标为敌对边界 */
	public static int hostileBorderExtensionChunks = 2;
	/** 世界日时间（0-24000）触发每日刷新的时刻 */
	public static int dailyRefreshTime = 0;
	/** 结构 sentinel 链式认领每 tick 最多改写的方块数 */
	public static int structureClaimBlocksPerTick = 512;
	/** 进入新所属领地时是否向玩家显示「xxx的领地」 */
	public static boolean showTerritoryEnterMessage = true;

	private TerritoryConfig() {
	}

	public static boolean isStructureSentinel(UUID owner) {
		return STRUCTURE_BLOCK_SENTINEL.equals(owner);
	}
}
