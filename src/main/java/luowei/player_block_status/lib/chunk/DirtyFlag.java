package luowei.player_block_status.lib.chunk;

/**
 * 脏页标记：区块数据变更后记录待算项，仅在每日日出全盘重算时消费。
 */
public enum DirtyFlag {
	BLOCK_SCORE,
	DEATH_SCORE,
	ORGANIZATION,
	STATE
}
