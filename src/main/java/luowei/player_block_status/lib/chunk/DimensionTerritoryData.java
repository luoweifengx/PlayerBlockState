package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * 维度级元数据：待认领结构、每日刷新进度与活跃区块键。
 * 区块领土本体存于各 {@link net.minecraft.world.level.chunk.LevelChunk} 的 Attachment。
 */
public class DimensionTerritoryData extends SavedData {
	private static final String DATA_ID = "player_block_status_territory";

	public static final Codec<DimensionTerritoryData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			StructureBounds.CODEC.listOf().fieldOf("structures").forGetter(data -> data.pendingStructures),
			Codec.LONG.listOf().optionalFieldOf("registered_structure_keys", List.of())
					.forGetter(data -> new ArrayList<>(data.registeredStructureInstanceKeys)),
			Codec.LONG.fieldOf("last_daily_day").forGetter(data -> data.lastDailyDay),
			Codec.LONG.listOf().optionalFieldOf("active_chunk_keys", List.of())
					.forGetter(data -> new ArrayList<>(data.activeChunkKeys))
	).apply(instance, (structures, registeredKeys, lastDailyDay, activeChunkKeys) -> {
		DimensionTerritoryData data = new DimensionTerritoryData();
		data.pendingStructures.addAll(structures);
		data.registeredStructureInstanceKeys.addAll(registeredKeys);
		data.lastDailyDay = lastDailyDay;
		data.activeChunkKeys.addAll(activeChunkKeys);
		return data;
	}));

	public static final SavedDataType<DimensionTerritoryData> TYPE = new SavedDataType<>(
			DATA_ID,
			context -> new DimensionTerritoryData(),
			context -> DimensionTerritoryData.CODEC,
			null
	);

	private final List<StructureBounds> pendingStructures = new ArrayList<>();
	private final Set<Long> registeredStructureInstanceKeys = new HashSet<>();
	private final Set<Long> activeChunkKeys = new HashSet<>();
	private final EntityChunkIndex entityChunkIndex = new EntityChunkIndex();
	private long lastDailyDay = -1;
	private boolean dailyRefreshInProgress;

	public static DimensionTerritoryData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	public List<StructureBounds> getPendingStructures() {
		return pendingStructures;
	}

	public Set<Long> getActiveChunkKeys() {
		return activeChunkKeys;
	}

	public EntityChunkIndex getEntityChunkIndex() {
		return entityChunkIndex;
	}

	public long getLastDailyDay() {
		return lastDailyDay;
	}

	public boolean tryBeginDailyRefresh(long currentDay) {
		if (dailyRefreshInProgress) {
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily] tryBeginDailyRefresh rejected: refresh already in progress (currentDay={}, lastDailyDay={})",
					currentDay,
					lastDailyDay
			);
			return false;
		}
		if (currentDay <= lastDailyDay) {
			PlayerBlockStatus.LOGGER.debug(
					"[pbs daily] tryBeginDailyRefresh rejected: day already processed (currentDay={}, lastDailyDay={})",
					currentDay,
					lastDailyDay
			);
			return false;
		}
		dailyRefreshInProgress = true;
		lastDailyDay = currentDay;
		setDirty();
		PlayerBlockStatus.LOGGER.info(
				"[pbs daily] tryBeginDailyRefresh accepted: currentDay={}, lastDailyDay now={}",
				currentDay,
				lastDailyDay
		);
		return true;
	}

	public void finishDailyRefresh() {
		dailyRefreshInProgress = false;
	}

	public void cancelDailyRefreshInProgress() {
		dailyRefreshInProgress = false;
	}

	public boolean tryMarkStructureInstanceRegistered(long instanceKey) {
		if (!registeredStructureInstanceKeys.add(instanceKey)) {
			return false;
		}
		setDirty();
		return true;
	}

	public void registerStructure(StructureBounds bounds) {
		pendingStructures.add(bounds);
		setDirty();
	}
}
