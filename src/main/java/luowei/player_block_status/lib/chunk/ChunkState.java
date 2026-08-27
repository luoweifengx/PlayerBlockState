package luowei.player_block_status.lib.chunk;

/**
 * 区块领土状态。3/4 为计算派生状态，不互相转换。
 */
public enum ChunkState {
	/** 自然状态：未有玩家分数抵达占领阈值 */
	NATURAL(1),
	/** 占领状态：记录占领组织，区域内部 */
	OCCUPIED(2),
	/** 边界状态：占领区域外框，本质仍属占领方 */
	BORDER(3),
	/** 敌对边界：与边界相邻的自然区块 */
	HOSTILE_BORDER(4),
	/** 安全区块：仅因死亡或群系/地势变更而转变 */
	SAFE(5),
	/** 死亡状态：分数低于死亡阈值 */
	DEATH(6),
	/** 恶魔区块：传送门激活生成，可日更扩散；不可被其它状态覆盖，可强制覆盖其它状态 */
	DEMON(7);

	private final int id;

	ChunkState(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public static ChunkState fromId(int id) {
		for (ChunkState state : values()) {
			if (state.id == id) {
				return state;
			}
		}
		throw new IllegalArgumentException("Unknown chunk state id: " + id);
	}

	public boolean isOccupiedFamily() {
		return this == OCCUPIED || this == BORDER;
	}

	public boolean isNaturalFamily() {
		return this == NATURAL || this == HOSTILE_BORDER;
	}

	public boolean isDemon() {
		return this == DEMON;
	}

	/** 其它区块类型不能覆盖恶魔区块；恶魔区块可以覆盖任意类型。 */
	public boolean canBeReplacedBy(ChunkState incoming) {
		if (this == DEMON) {
			return incoming == DEMON;
		}
		return true;
	}
}
