package luowei.player_block_status.lib.api;

/**
 * 当前正在运作的供奉信标快照：数量与最高金字塔等级。
 */
public record BeaconOfferingSnapshot(int operatingCount, int maxLevel) {
	public static BeaconOfferingSnapshot none() {
		return new BeaconOfferingSnapshot(0, 0);
	}

	public boolean hasOperatingBeacon() {
		return operatingCount > 0;
	}
}
