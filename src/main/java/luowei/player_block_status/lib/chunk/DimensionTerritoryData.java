package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * 维度级元数据：待认领结构（兼容旧存档/外部 API）、每日刷新进度、活跃/脏页区块键与占领倒排索引。
 * 区块领土本体存于各 {@link net.minecraft.world.level.chunk.LevelChunk} 的 Attachment。
 * 结构认领已改为生成时 sentinel + 玩家放置链式传播，pending 列表不再驱动 flood。
 */
public class DimensionTerritoryData extends SavedData {
	private static final String DATA_ID = "player_block_status_territory";

	public static final Codec<DimensionTerritoryData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			StructureBounds.CODEC.listOf().fieldOf("structures").forGetter(data -> data.pendingStructures),
			Codec.LONG.listOf().optionalFieldOf("registered_structure_keys", List.of())
					.forGetter(data -> new ArrayList<>(data.registeredStructureInstanceKeys)),
			Codec.LONG.fieldOf("last_daily_day").forGetter(data -> data.lastDailyDay),
			Codec.LONG.listOf().optionalFieldOf("active_chunk_keys", List.of())
					.forGetter(data -> new ArrayList<>(data.activeChunkKeys)),
			Codec.LONG.listOf().optionalFieldOf("dirty_chunk_keys", List.of())
					.forGetter(data -> new ArrayList<>(data.dirtyChunkKeys)),
			TerritoryCodec.longKeyMap(Codec.INT).optionalFieldOf("dirty_chunk_epochs", Map.of())
					.forGetter(data -> new HashMap<>(data.dirtyChunkEpochs)),
			TerritoryCodec.UUID_LONG_SET_MAP.optionalFieldOf("occupied_index", Map.of())
					.forGetter(data -> data.entityChunkIndex.copyOccupiedForPersist()),
			TerritoryCodec.UUID_LONG_SET_MAP.optionalFieldOf("border_index", Map.of())
					.forGetter(data -> data.entityChunkIndex.copyBorderForPersist())
	).apply(instance, (structures, registeredKeys, lastDailyDay, activeChunkKeys, dirtyChunkKeys, dirtyChunkEpochs, occupiedIndex, borderIndex) -> {
		DimensionTerritoryData data = new DimensionTerritoryData();
		data.pendingStructures.addAll(structures);
		data.registeredStructureInstanceKeys.addAll(registeredKeys);
		data.lastDailyDay = lastDailyDay;
		data.activeChunkKeys.addAll(activeChunkKeys);
		data.dirtyChunkKeys.addAll(dirtyChunkKeys);
		data.dirtyChunkEpochs.putAll(dirtyChunkEpochs);
		for (long key : data.dirtyChunkKeys) {
			data.dirtyChunkEpochs.putIfAbsent(key, 0);
		}
		data.dirtyChunkEpochs.keySet().retainAll(data.dirtyChunkKeys);
		data.entityChunkIndex.load(occupiedIndex, borderIndex);
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
	private final Set<Long> dirtyChunkKeys = new HashSet<>();
	/** 每个 dirty key 的世代；标脏时即使 key 已在集合中也 +1，用于日更清脏时跳过期间再次标脏的页。 */
	private final Map<Long, Integer> dirtyChunkEpochs = new HashMap<>();
	private final EntityChunkIndex entityChunkIndex = new EntityChunkIndex(this::setDirty);
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

	/** 脏页键的不可变快照；增删请走 {@link #markChunkDirty}/{@link #clearRecomputedDirtyKeys}。 */
	public Set<Long> getDirtyChunkKeys() {
		return Set.copyOf(dirtyChunkKeys);
	}

	/**
	 * 将区块标脏：key 加入集合，且无论是否已在集合中都把 epoch +1。
	 *
	 * @return 是否为新加入的 dirty key（epoch 始终会推进）
	 */
	public boolean markChunkDirty(long chunkKey) {
		boolean added = dirtyChunkKeys.add(chunkKey);
		dirtyChunkEpochs.merge(chunkKey, 1, Integer::sum);
		setDirty();
		return added;
	}

	/** 开算时对指定 keys 拍摄 epoch 快照，供 apply 清脏比对。 */
	public Map<Long, Integer> snapshotDirtyEpochs(Set<Long> keys) {
		Map<Long, Integer> snapshot = new HashMap<>(keys.size());
		for (long key : keys) {
			snapshot.put(key, dirtyChunkEpochs.getOrDefault(key, 0));
		}
		return snapshot;
	}

	/**
	 * apply 成功后清脏：仅当当前 epoch 仍等于开算快照时才移除；
	 * 期间又被标脏（epoch 已前进）的留下，下一天再算。
	 */
	public boolean clearRecomputedDirtyKeys(Map<Long, Integer> epochSnapshot) {
		if (epochSnapshot == null || epochSnapshot.isEmpty()) {
			return false;
		}
		boolean changed = false;
		for (Map.Entry<Long, Integer> entry : epochSnapshot.entrySet()) {
			long key = entry.getKey();
			int beginEpoch = entry.getValue();
			Integer currentEpoch = dirtyChunkEpochs.get(key);
			if (currentEpoch != null && currentEpoch == beginEpoch) {
				dirtyChunkKeys.remove(key);
				dirtyChunkEpochs.remove(key);
				changed = true;
			}
		}
		if (changed) {
			setDirty();
		}
		return changed;
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
		return beginDailyRefresh(currentDay);
	}

	/** 调试用：允许同一天重复重算，仍拒绝并发刷新。 */
	public boolean tryBeginDailyRefreshForce(long currentDay) {
		if (dailyRefreshInProgress) {
			PlayerBlockStatus.LOGGER.info(
					"[pbs daily] tryBeginDailyRefreshForce rejected: refresh already in progress (currentDay={}, lastDailyDay={})",
					currentDay,
					lastDailyDay
			);
			return false;
		}
		return beginDailyRefresh(currentDay);
	}

	/**
	 * 脏页为空时仍推进 {@code lastDailyDay}，避免每个 tick 重复尝试日更。
	 */
	public boolean acknowledgeIdleDaily(long currentDay) {
		if (dailyRefreshInProgress || currentDay <= lastDailyDay) {
			return false;
		}
		lastDailyDay = currentDay;
		setDirty();
		return true;
	}

	private boolean beginDailyRefresh(long currentDay) {
		dailyRefreshInProgress = true;
		PlayerBlockStatus.LOGGER.info(
				"[pbs daily] daily refresh begun: currentDay={}, lastDailyDay={}",
				currentDay,
				lastDailyDay
		);
		return true;
	}

	/** apply 成功末尾：清 in-progress 并推进 {@code lastDailyDay}。 */
	public void finishDailyRefresh(long currentDay) {
		dailyRefreshInProgress = false;
		lastDailyDay = currentDay;
		setDirty();
	}

	/** 失败 cancel：只清 in-progress，不推进 day。 */
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
