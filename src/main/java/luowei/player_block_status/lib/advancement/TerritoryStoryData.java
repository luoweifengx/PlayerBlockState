package luowei.player_block_status.lib.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 全员进度相关的世界故事旗标（主世界 SavedData）。
 * 与 {@code DemonChunkWorldData} 的当前传送门列表分开：拆门不会清这些「曾经发生过」的标记。
 */
public class TerritoryStoryData extends SavedData {
	private static final String DATA_ID = "player_block_status_story";

	public static final Codec<TerritoryStoryData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("first_nether_portal_opened", false)
					.forGetter(data -> data.firstNetherPortalOpened),
			Codec.BOOL.optionalFieldOf("level3_beacon_activated", false)
					.forGetter(data -> data.level3BeaconActivated)
	).apply(instance, (firstPortal, level3Beacon) -> {
		TerritoryStoryData data = new TerritoryStoryData();
		data.firstNetherPortalOpened = firstPortal;
		data.level3BeaconActivated = level3Beacon;
		return data;
	}));

	public static final SavedDataType<TerritoryStoryData> TYPE = new SavedDataType<>(
			DATA_ID,
			context -> new TerritoryStoryData(),
			context -> TerritoryStoryData.CODEC,
			null
	);

	private boolean firstNetherPortalOpened;
	private boolean level3BeaconActivated;

	public static TerritoryStoryData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public boolean isFirstNetherPortalOpened() {
		return firstNetherPortalOpened;
	}

	public boolean isLevel3BeaconActivated() {
		return level3BeaconActivated;
	}

	/** @return 本次调用是否新置位（已经立过则 false） */
	public boolean markFirstNetherPortalOpened() {
		if (firstNetherPortalOpened) {
			return false;
		}
		firstNetherPortalOpened = true;
		setDirty();
		return true;
	}

	/** @return 本次调用是否新置位（已经立过则 false） */
	public boolean markLevel3BeaconActivated() {
		if (level3BeaconActivated) {
			return false;
		}
		level3BeaconActivated = true;
		setDirty();
		return true;
	}
}
