package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import luowei.player_block_status.lib.api.BeaconOfferingSnapshot;

/**
 * 全服恶魔区块运行时标志、运作信标与已知地狱传送门位置。
 * 存于主世界 SavedData，各维度共享（信标效果作用于全部维度）。
 */
public class DemonChunkWorldData extends SavedData {
	private static final String DATA_ID = "player_block_status_demon";

	public record StoredPos(String dimension, int x, int y, int z, int level) {
		public static final Codec<StoredPos> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("dimension").forGetter(StoredPos::dimension),
				Codec.INT.fieldOf("x").forGetter(StoredPos::x),
				Codec.INT.fieldOf("y").forGetter(StoredPos::y),
				Codec.INT.fieldOf("z").forGetter(StoredPos::z),
				Codec.INT.optionalFieldOf("level", 0).forGetter(StoredPos::level)
		).apply(instance, StoredPos::new));

		public static StoredPos of(ResourceKey<Level> dimension, BlockPos pos, int level) {
			return new StoredPos(dimension.location().toString(), pos.getX(), pos.getY(), pos.getZ(), level);
		}

		public String key() {
			return dimension + "|" + x + "|" + y + "|" + z;
		}

		public BlockPos blockPos() {
			return new BlockPos(x, y, z);
		}

		public ResourceLocation dimensionLocation() {
			return ResourceLocation.parse(dimension);
		}

		public StoredPos withLevel(int newLevel) {
			return new StoredPos(dimension, x, y, z, newLevel);
		}
	}

	public static final Codec<DemonChunkWorldData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.DOUBLE.optionalFieldOf("spread_probability", TerritoryConfig.DEMON_SPREAD_PROBABILITY_DEFAULT)
					.forGetter(data -> data.flags.spreadProbability()),
			Codec.BOOL.optionalFieldOf("spreading_enabled", true)
					.forGetter(data -> data.flags.spreadingEnabled()),
			Codec.BOOL.optionalFieldOf("generation_forbidden", false)
					.forGetter(data -> data.flags.generationForbidden()),
			StoredPos.CODEC.listOf().optionalFieldOf("beacons", List.of())
					.forGetter(data -> new ArrayList<>(data.beacons.values())),
			StoredPos.CODEC.listOf().optionalFieldOf("nether_portals", List.of())
					.forGetter(data -> new ArrayList<>(data.netherPortals.values()))
	).apply(instance, (probability, spreading, forbidden, beacons, portals) -> {
		DemonChunkWorldData data = new DemonChunkWorldData();
		data.flags = new DemonChunkRules.Flags(probability, spreading, forbidden);
		for (StoredPos beacon : beacons) {
			data.beacons.put(beacon.key(), beacon);
		}
		for (StoredPos portal : portals) {
			data.netherPortals.put(portal.key(), portal);
		}
		return data;
	}));

	public static final SavedDataType<DemonChunkWorldData> TYPE = new SavedDataType<>(
			DATA_ID,
			context -> new DemonChunkWorldData(),
			context -> DemonChunkWorldData.CODEC,
			null
	);

	private DemonChunkRules.Flags flags = DemonChunkRules.Flags.defaults();
	private final Map<String, StoredPos> beacons = new HashMap<>();
	private final Map<String, StoredPos> netherPortals = new HashMap<>();

	public static DemonChunkWorldData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public DemonChunkRules.Flags getFlags() {
		return flags;
	}

	public void setFlags(DemonChunkRules.Flags flags) {
		this.flags = flags == null ? DemonChunkRules.Flags.defaults() : flags;
		setDirty();
	}

	public boolean isSpreadingEnabled() {
		return flags.spreadingEnabled();
	}

	public boolean isGenerationForbidden() {
		return flags.generationForbidden();
	}

	public double getSpreadProbability() {
		return flags.spreadProbability();
	}

	public BeaconOfferingSnapshot snapshot() {
		int maxLevel = 0;
		for (StoredPos beacon : beacons.values()) {
			if (beacon.level() > maxLevel) {
				maxLevel = beacon.level();
			}
		}
		return new BeaconOfferingSnapshot(beacons.size(), maxLevel);
	}

	/**
	 * 更新一座信标的运作等级。{@code level <= 0} 视为停止供奉。
	 *
	 * @return 数量或最高等级是否变化；未变则 empty
	 */
	public BeaconChange updateBeacon(ResourceKey<Level> dimension, BlockPos pos, int level) {
		BeaconOfferingSnapshot previous = snapshot();
		String key = StoredPos.of(dimension, pos, level).key();
		if (level <= 0) {
			if (beacons.remove(key) == null) {
				return BeaconChange.unchanged(previous);
			}
		} else {
			StoredPos existing = beacons.get(key);
			if (existing != null && existing.level() == level) {
				return BeaconChange.unchanged(previous);
			}
			beacons.put(key, StoredPos.of(dimension, pos, level));
		}
		setDirty();
		BeaconOfferingSnapshot current = snapshot();
		if (previous.equals(current)) {
			return BeaconChange.unchanged(current);
		}
		return new BeaconChange(true, previous, current);
	}

	public BeaconChange removeBeacon(ResourceKey<Level> dimension, BlockPos pos) {
		return updateBeacon(dimension, pos, 0);
	}

	public List<StoredPos> copyBeacons() {
		return new ArrayList<>(beacons.values());
	}

	public void trackNetherPortal(ResourceKey<Level> dimension, BlockPos pos) {
		StoredPos stored = StoredPos.of(dimension, pos, 0);
		if (netherPortals.put(stored.key(), stored) == null) {
			setDirty();
		}
	}

	public void untrackNetherPortal(ResourceKey<Level> dimension, BlockPos pos) {
		if (netherPortals.remove(StoredPos.of(dimension, pos, 0).key()) != null) {
			setDirty();
		}
	}

	public Set<StoredPos> copyNetherPortals() {
		return new HashSet<>(netherPortals.values());
	}

	public void clearNetherPortals() {
		if (!netherPortals.isEmpty()) {
			netherPortals.clear();
			setDirty();
		}
	}

	public record BeaconChange(boolean changed, BeaconOfferingSnapshot previous, BeaconOfferingSnapshot current) {
		static BeaconChange unchanged(BeaconOfferingSnapshot snapshot) {
			return new BeaconChange(false, snapshot, snapshot);
		}
	}
}
