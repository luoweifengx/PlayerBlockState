package luowei.player_block_status.lib.chunk;

import java.util.Locale;

/**
 * 敌对边界生成模式，二者互斥。
 */
public enum HostileBorderMode {
	/** 感染：半拍抽取玩家边界，写成敌对边界 / 敌对占领 */
	INFECTION("infection"),
	/** 传播：日更从边界向外延伸敌对边界 */
	SPREAD("spread");

	private final String id;

	HostileBorderMode(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static HostileBorderMode fromId(String raw, HostileBorderMode fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		String id = raw.trim().toLowerCase(Locale.ROOT);
		for (HostileBorderMode mode : values()) {
			if (mode.id.equals(id) || mode.name().equalsIgnoreCase(id)) {
				return mode;
			}
		}
		return fallback;
	}
}
