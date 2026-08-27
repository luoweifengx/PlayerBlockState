package luowei.player_block_status.lib.api;

import net.minecraft.server.MinecraftServer;

/**
 * 信标开始/停止供奉（金字塔等级在 0 与 ≥1 之间变化，或最高等级变化）时的监听。
 * 用于叠加修改恶魔区块扩散概率、是否允许扩散/生成等运行时值。
 */
@FunctionalInterface
public interface BeaconOfferingListener {
	void onBeaconOfferingChanged(
			MinecraftServer server,
			BeaconOfferingSnapshot previous,
			BeaconOfferingSnapshot current
	);
}
