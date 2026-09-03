package luowei.player_block_status.lib.chunk;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 领土系统阈值与加分常量。启动时从 {@code config/player-block-status.json} 读入，
 * 也可由消费模组在其后覆盖。
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
	/** 每放置方块得分 */
	public static int blockScorePerBlock = 20;
	public static int stayScorePerInterval = 5;
	public static int stayTickInterval = 100;
	public static int deathPenalty = -50;
	public static int deathRecoveryPerDay = 30;
	/** 从占领区块夺走所属：挑战者分数须 ≥ 当前所属分数 × 此倍率 */
	public static double occupationTakeoverMultiplier = 2.5;
	/** 从边界区块夺走所属：挑战者分数须 ≥ 当前所属分数 × 此倍率；亦用于敌对边界入境 */
	public static double borderTakeoverMultiplier = 1.9;
	/**
	 * 敌对边界生成模式，二者互斥。默认 {@link HostileBorderMode#INFECTION 感染}；
	 * {@link HostileBorderMode#SPREAD 传播} 时日更按 {@link #hostileBorderExtensionChunks} 派生敌对边界。
	 */
	public static HostileBorderMode hostileBorderMode = HostileBorderMode.INFECTION;
	/** 传播模式下，从边界区块向外延伸的自然区块层数（切比雪夫距离），标为敌对边界 */
	public static int hostileBorderExtensionChunks = 2;
	/** 世界日时间（0-24000）触发每日刷新的时刻；结算已改按 {@link #refreshIntervalTicks}，此值不再参与调度 */
	public static int dailyRefreshTime = 0;
	/** 领土结算周期（游戏 tick）。{@code gameTime % refreshIntervalTicks == 0} 进入新周期时调度脏页重算 */
	public static int refreshIntervalTicks = 3000;
	/** 结构 sentinel 刷写与链式认领每 tick 最多改写的方块数（配置项 `structureClaimBlocksPerTick`） */
	public static int structureClaimBlocksPerTick = 32386;
	/** 进入自己的领地时的默认提示（可被客户端配置 / {@code /pbs territory backmine} 覆盖） */
	public static final String DEFAULT_OWN_TERRITORY_ENTER_MESSAGE = "自己的领地";
	public static String ownTerritoryEnterMessage = DEFAULT_OWN_TERRITORY_ENTER_MESSAGE;
	/** 进入自己的领地时的显示类型：{@code sight of me} 用上面的文案；{@code sight of others} 用公开地区名；{@code off} 关闭提示 */
	public static String enterMessageInfoType = "sight of me";
	/** 进入新所属领地时是否向玩家显示地区名 */
	public static boolean showTerritoryEnterMessage = true;
	/** 恶魔区块默认日更扩散进入概率（先掷骰，再随机挑一个四邻） */
	public static final double DEMON_SPREAD_PROBABILITY_DEFAULT = 0.01;
	/** 1 级供奉信标生效时的扩散进入概率 */
	public static final double DEMON_SPREAD_PROBABILITY_LEVEL1 = 0.001;

	private TerritoryConfig() {
	}

	public static boolean isStructureSentinel(UUID owner) {
		return STRUCTURE_BLOCK_SENTINEL.equals(owner);
	}

	public static boolean isInfectionMode() {
		return hostileBorderMode == HostileBorderMode.INFECTION;
	}

	public static boolean isSpreadMode() {
		return hostileBorderMode == HostileBorderMode.SPREAD;
	}

	/**
	 * 从未占领态进入占领所需分数。敌对边界 / 敌对占领按 {@link #occupationThreshold} × {@link #borderTakeoverMultiplier}。
	 */
	public static int occupationScoreRequired(ChunkState previous) {
		if (previous == ChunkState.HOSTILE_BORDER || previous == ChunkState.HOSTILE) {
			return (int) Math.ceil(occupationThreshold * borderTakeoverMultiplier);
		}
		return occupationThreshold;
	}
}
