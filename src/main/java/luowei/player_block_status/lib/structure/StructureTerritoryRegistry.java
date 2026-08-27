package luowei.player_block_status.lib.structure;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * 结构类型注册表：决定哪些 {@link Structure} 在世界生成时把模板方块标为 sentinel。
 * 默认跟踪全部结构，但排除大体量原版结构，避免进档时逐块入队打满服务端。
 * 黑名单只跳过 sentinel 捕获；对应区块仍可靠玩家放置/停留分正常占领。
 * 可通过白名单/黑名单继续收窄。
 */
public final class StructureTerritoryRegistry {
	public static final StructureTerritoryRegistry INSTANCE = new StructureTerritoryRegistry();

	private final Set<ResourceKey<Structure>> whitelist = new HashSet<>();
	private final Set<ResourceKey<Structure>> blacklist = new HashSet<>();
	private boolean whitelistOnly;

	private StructureTerritoryRegistry() {
		ignore(vanilla("trial_chambers"));
		ignore(vanilla("mineshaft"));
		ignore(vanilla("mineshaft_mesa"));
		ignore(vanilla("ancient_city"));
		ignore(vanilla("stronghold"));
		ignore(vanilla("fortress"));
		ignore(vanilla("bastion_remnant"));
		ignore(vanilla("mansion"));
		ignore(vanilla("monument"));
	}

	private static ResourceKey<Structure> vanilla(String path) {
		return ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace(path));
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
