package luowei.player_block_status.lib.api;

import luowei.player_block_status.lib.structure.StructureTerritoryRegistry;

/**
 * 第三方模组在 {@code fabric.mod.json} 中声明
 * {@code "player-block-status:structure_territory"} entrypoint 并实现此接口，
 * 以注册需要跟踪的结构类型。
 */
@FunctionalInterface
public interface StructureTerritoryContributor {
	void registerStructureTerritory(StructureTerritoryRegistry registry);
}
