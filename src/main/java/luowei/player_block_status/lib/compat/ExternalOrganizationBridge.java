package luowei.player_block_status.lib.compat;

import luowei.player_block_status.lib.api.OrganizationProvider;

/**
 * 计分账户解析链上的外部空档：先问这里，再问本库内置组织，都 empty 则记到玩家 UUID。
 * <p>
 * 本轮恒为 {@link #NONE}，不接 FTB Teams 运行时。以后若安装 FTB，经 {@link #set} 挂上
 * Party 解析即可，不必调用 {@code setOrganizationProvider} 盖掉内置回退。
 * <p>
 * 以后接 FTB 时必须只返回 Party UUID，不能把 Player Team（id = 玩家 UUID）当成组织，
 * 否则内置组织永远走不到。
 */
public final class ExternalOrganizationBridge {
	private ExternalOrganizationBridge() {
	}

	/** 本轮外部层：始终 empty。 */
	public static final OrganizationProvider NONE = OrganizationProvider.NONE;

	private static volatile OrganizationProvider current = NONE;

	public static OrganizationProvider current() {
		return current;
	}

	/**
	 * 安装外部账户层（例如未来的 FTB Party）。传入 {@code null} 则恢复 {@link #NONE}。
	 * 不会替换内置组织回退。
	 */
	public static void set(OrganizationProvider provider) {
		current = provider == null ? NONE : provider;
	}
}
