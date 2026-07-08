package luowei.player_block_status.lib.chunk;

/**
 * 领土系统阈值与加分常量，可由消费模组在启动时覆盖。
 */
public final class TerritoryConfig {
	/** 进入占领状态所需分数 */
	public static int occupationThreshold = 1000;
	/** 从占领/边界退回自然状态：所有玩家分数均低于此值 */
	public static int naturalReturnThreshold = 500;
	public static int deathThreshold = -100;
	public static int blockScorePerBlock = 4;
	public static int stayScorePerInterval = 3;
	public static int stayTickInterval = 100;
	public static int deathPenalty = -50;
	public static int deathRecoveryPerDay = 30;
	/** 世界日时间（0-24000）触发每日刷新的时刻 */
	public static int dailyRefreshTime = 0;

	private TerritoryConfig() {
	}
}
