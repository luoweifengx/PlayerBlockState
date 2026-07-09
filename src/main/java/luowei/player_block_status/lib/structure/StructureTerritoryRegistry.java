package luowei.player_block_status.lib.structure;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * 结构类型注册表：决定哪些 {@link Structure} 在世界生成后写入待认领列表。
 * 默认跟踪全部结构；可通过白名单/黑名单收窄。
 */
public final class StructureTerritoryRegistry {
	public static final StructureTerritoryRegistry INSTANCE = new StructureTerritoryRegistry();

	private final Set<ResourceKey<Structure>> whitelist = new HashSet<>();
	private final Set<ResourceKey<Structure>> blacklist = new HashSet<>();
	private boolean whitelistOnly;

	private StructureTerritoryRegistry() {
	}

	public StructureTerritoryRegistry enableWhitelistOnly() {
		this.whitelistOnly = true;
		return this;
	}

	public StructureTerritoryRegistry track(ResourceKey<Structure> structureKey) {
		whitelist.add(structureKey);
		return this;
	}

	public StructureTerritoryRegistry ignore(ResourceKey<Structure> structureKey) {
		blacklist.add(structureKey);
		return this;
	}

	public boolean shouldTrack(ResourceKey<Structure> structureKey) {
		if (blacklist.contains(structureKey)) {
			return false;
		}
		if (whitelistOnly && !whitelist.isEmpty()) {
			return whitelist.contains(structureKey);
		}
		return true;
	}
}
