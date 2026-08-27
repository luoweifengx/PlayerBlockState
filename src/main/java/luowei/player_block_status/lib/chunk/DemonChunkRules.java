package luowei.player_block_status.lib.chunk;

/**
 * 恶魔区块运行时规则：由当前运作信标的最高等级叠加修改。
 * <ul>
 *   <li>1 级：扩散概率 0.001</li>
 *   <li>2 级：停止扩散</li>
 *   <li>3 级及以上：清除全部恶魔区块，并禁止任何生成（传送门与日更扩散）</li>
 *   <li>运作信标数量归零：恢复默认概率 0.01、允许扩散与生成，并触发破坏地狱传送门</li>
 * </ul>
 * 降级时只要仍有信标在运作，就不触发归零重置，只按剩余最高等级生效。
 */
public final class DemonChunkRules {
	private DemonChunkRules() {
	}

	public record Flags(
			double spreadProbability,
			boolean spreadingEnabled,
			boolean generationForbidden
	) {
		public static Flags defaults() {
			return new Flags(
					TerritoryConfig.DEMON_SPREAD_PROBABILITY_DEFAULT,
					true,
					false
			);
		}
	}

	public record RecomputeResult(
			Flags flags,
			boolean fireReset,
			boolean clearAllDemonChunks
	) {
	}

	/**
	 * 按当前运作信标数量与最高等级重算标志。
	 *
	 * @param operatingCount 当前运作信标数
	 * @param maxLevel 当前最高金字塔等级（无信标为 0）
	 * @param previousCount 变更前数量
	 * @param previousMaxLevel 变更前最高等级
	 */
	public static RecomputeResult recompute(
			int operatingCount,
			int maxLevel,
			int previousCount,
			int previousMaxLevel
	) {
		if (operatingCount <= 0) {
			boolean fireReset = previousCount > 0;
			return new RecomputeResult(Flags.defaults(), fireReset, false);
		}

		Flags flags = Flags.defaults();
		boolean clearAll = false;

		if (maxLevel >= 1) {
			flags = new Flags(TerritoryConfig.DEMON_SPREAD_PROBABILITY_LEVEL1, true, false);
		}
		if (maxLevel >= 2) {
			flags = new Flags(0.0d, false, flags.generationForbidden());
		}
		if (maxLevel >= 3) {
			flags = new Flags(0.0d, false, true);
			clearAll = previousMaxLevel < 3;
		}

		return new RecomputeResult(flags, false, clearAll);
	}

	public static boolean shouldCreateDemonFromPortal(boolean generationForbidden) {
		return !generationForbidden;
	}
}
