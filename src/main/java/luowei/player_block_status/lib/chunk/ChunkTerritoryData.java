package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * 单个区块的领土数据：玩家放置方块、分数、状态与脏页标记。
 */
public class ChunkTerritoryData {
	public static final Codec<ChunkTerritoryData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			TerritoryCodec.longKeyMap(UUIDUtil.CODEC).fieldOf("placed_blocks").forGetter(data -> data.placedBlocks),
			TerritoryCodec.UUID_INT_MAP.fieldOf("score_modifiers").forGetter(data -> data.scoreModifiers),
			TerritoryCodec.UUID_INT_MAP.optionalFieldOf("stay_scores", Map.of()).forGetter(data -> data.stayScores),
			Codec.INT.fieldOf("state_id").forGetter(data -> data.state.getId()),
			UUIDUtil.CODEC.optionalFieldOf("occupying_org").forGetter(data -> java.util.Optional.ofNullable(data.occupyingOrg))
	).apply(instance, (placedBlocks, scoreModifiers, stayScores, stateId, occupyingOrg) -> {
		ChunkTerritoryData data = new ChunkTerritoryData();
		data.placedBlocks.putAll(placedBlocks);
		data.scoreModifiers.putAll(scoreModifiers);
		data.stayScores.putAll(stayScores);
		data.state = ChunkState.fromId(stateId);
		data.occupyingOrg = occupyingOrg.orElse(null);
		return data;
	}));

	/** 区块内局部坐标（packLocalPos）→ 放置者 UUID */
	private final Map<Long, UUID> placedBlocks = new HashMap<>();
	/** 死亡惩罚与每日恢复等非方块、非停留分数修正 */
	private final Map<UUID, Integer> scoreModifiers = new HashMap<>();
	/** 当日累计停留分，每日重算结束后清零 */
	private final Map<UUID, Integer> stayScores = new HashMap<>();
	/** 运行时缓存的总分，脏页重算后更新 */
	private final Map<UUID, Integer> cachedScores = new HashMap<>();

	private ChunkState state = ChunkState.NATURAL;
	private UUID occupyingOrg;
	private final EnumSet<DirtyFlag> dirtyFlags = EnumSet.noneOf(DirtyFlag.class);

	public static ChunkTerritoryData createEmpty() {
		return new ChunkTerritoryData();
	}

	public Map<Long, UUID> getPlacedBlocks() {
		return placedBlocks;
	}

	public Map<UUID, Integer> getScoreModifiers() {
		return scoreModifiers;
	}

	public Map<UUID, Integer> getStayScores() {
		return stayScores;
	}

	public Map<UUID, Integer> getCachedScores() {
		return cachedScores;
	}

	public ChunkState getState() {
		return state;
	}

	public void setState(ChunkState state) {
		this.state = state;
	}

	public UUID getOccupyingOrg() {
		return occupyingOrg;
	}

	public void setOccupyingOrg(UUID occupyingOrg) {
		this.occupyingOrg = occupyingOrg;
	}

	public EnumSet<DirtyFlag> getDirtyFlags() {
		return dirtyFlags;
	}

	public void markDirty(DirtyFlag flag) {
		dirtyFlags.add(flag);
	}

	public boolean isDirty() {
		return !dirtyFlags.isEmpty();
	}

	public void clearDirty() {
		dirtyFlags.clear();
	}

	public void addPlacedBlock(BlockPos globalPos, UUID owner) {
		placedBlocks.put(packLocalPos(globalPos), owner);
		markDirty(DirtyFlag.BLOCK_SCORE);
		markDirty(DirtyFlag.STATE);
	}

	public void removePlacedBlock(BlockPos globalPos) {
		if (placedBlocks.remove(packLocalPos(globalPos)) != null) {
			markDirty(DirtyFlag.BLOCK_SCORE);
			markDirty(DirtyFlag.STATE);
		}
	}

	public UUID getPlacedBlockOwner(BlockPos globalPos) {
		return placedBlocks.get(packLocalPos(globalPos));
	}

	public boolean hasEntityPresence(UUID entityId) {
		return scoreModifiers.containsKey(entityId)
				|| stayScores.containsKey(entityId)
				|| placedBlocks.containsValue(entityId);
	}

	public boolean referencesEntity(UUID entityId) {
		return entityId.equals(occupyingOrg) || hasEntityPresence(entityId);
	}

	public Set<UUID> collectPresentEntities() {
		Set<UUID> entities = new HashSet<>(scoreModifiers.keySet());
		entities.addAll(stayScores.keySet());
		entities.addAll(placedBlocks.values());
		return entities;
	}

	public boolean hasTerritoryData() {
		return !placedBlocks.isEmpty() || !scoreModifiers.isEmpty() || !stayScores.isEmpty();
	}

	public void accumulateStayScore(UUID entityId, int delta) {
		stayScores.merge(entityId, delta, Integer::sum);
	}

	public void clearStayScores() {
		stayScores.clear();
	}

	public void addDeathPenalty(UUID entityId, int delta) {
		scoreModifiers.merge(entityId, delta, Integer::sum);
		markDirty(DirtyFlag.DEATH_SCORE);
		markDirty(DirtyFlag.STATE);
	}

	public void remapEntity(UUID from, UUID to) {
		remapEntity(from, to, true);
	}

	/** 组织迁移等场景：只改 UUID，不标脏页。 */
	public void remapEntitySilent(UUID from, UUID to) {
		remapEntity(from, to, false);
	}

	private void remapEntity(UUID from, UUID to, boolean markDirtyFlags) {
		boolean changed = false;

		for (Map.Entry<Long, UUID> entry : new ArrayList<>(placedBlocks.entrySet())) {
			if (entry.getValue().equals(from)) {
				entry.setValue(to);
				changed = true;
			}
		}

		Integer modifier = scoreModifiers.remove(from);
		if (modifier != null) {
			scoreModifiers.merge(to, modifier, Integer::sum);
			changed = true;
		}

		Integer stay = stayScores.remove(from);
		if (stay != null) {
			stayScores.merge(to, stay, Integer::sum);
			changed = true;
		}

		if (from.equals(occupyingOrg)) {
			occupyingOrg = to;
			changed = true;
		}

		if (changed && markDirtyFlags) {
			markDirty(DirtyFlag.ORGANIZATION);
			markDirty(DirtyFlag.BLOCK_SCORE);
			markDirty(DirtyFlag.STATE);
		}
	}

	public static long packLocalPos(BlockPos globalPos) {
		int localX = globalPos.getX() & 15;
		int localZ = globalPos.getZ() & 15;
		int y = globalPos.getY() + 64;
		return ((long) y << 8) | ((long) localZ << 4) | localX;
	}

	public static BlockPos unpackGlobalPos(long chunkKey, long localPacked) {
		int chunkX = (int) (chunkKey & 0xFFFFFFFFL);
		int chunkZ = (int) (chunkKey >> 32);
		int localX = (int) (localPacked & 0xF);
		int localZ = (int) ((localPacked >> 4) & 0xF);
		int y = (int) ((localPacked >> 8) & 0xFFFFF) - 64;
		return new BlockPos((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
	}

	public List<BlockPos> getPlacedBlockGlobalPositions(long chunkKey) {
		List<BlockPos> positions = new ArrayList<>(placedBlocks.size());
		for (long localPacked : placedBlocks.keySet()) {
			positions.add(unpackGlobalPos(chunkKey, localPacked));
		}
		return positions;
	}
}
