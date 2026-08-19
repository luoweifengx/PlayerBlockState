package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;

/**
 * 领土数据持久化 Codec 工具。NBT CompoundTag 的键必须是字符串。
 */
public final class TerritoryCodec {
	public static final Codec<Long> LONG_AS_STRING = Codec.STRING.xmap(
			Long::parseLong,
			String::valueOf
	);

	public static final Codec<Map<UUID, Integer>> UUID_INT_MAP = Codec.unboundedMap(Codec.STRING, Codec.INT).xmap(
			stringMap -> {
				Map<UUID, Integer> result = new HashMap<>();
				stringMap.forEach((key, value) -> result.put(UUID.fromString(key), value));
				return result;
			},
			uuidMap -> {
				Map<String, Integer> result = new HashMap<>();
				uuidMap.forEach((key, value) -> result.put(key.toString(), value));
				return result;
			}
	);

	public static final Codec<Map<UUID, Set<Long>>> UUID_LONG_SET_MAP = Codec.unboundedMap(Codec.STRING, Codec.LONG.listOf()).xmap(
			stringMap -> {
				Map<UUID, Set<Long>> result = new HashMap<>();
				stringMap.forEach((key, value) -> result.put(UUID.fromString(key), new HashSet<>(value)));
				return result;
			},
			uuidMap -> {
				Map<String, List<Long>> result = new HashMap<>();
				uuidMap.forEach((key, value) -> result.put(key.toString(), new ArrayList<>(value)));
				return result;
			}
	);

	public static <T> Codec<Map<Long, T>> longKeyMap(Codec<T> valueCodec) {
		return Codec.unboundedMap(Codec.STRING, valueCodec).xmap(
				stringMap -> {
					Map<Long, T> result = new HashMap<>();
					stringMap.forEach((key, value) -> result.put(Long.parseLong(key), value));
					return result;
				},
				longMap -> {
					Map<String, T> result = new HashMap<>();
					longMap.forEach((key, value) -> result.put(String.valueOf(key), value));
					return result;
				}
		);
	}

	private TerritoryCodec() {
	}
}
