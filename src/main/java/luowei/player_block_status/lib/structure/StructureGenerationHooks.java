package luowei.player_block_status.lib.structure;

import java.util.Optional;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.chunk.RegionManager;
import luowei.player_block_status.lib.chunk.StructureBounds;
import luowei.player_block_status.lib.chunk.WorldRegionData;

/**
 * 结构在世界生成落地后登记到维度待认领列表。
 */
public final class StructureGenerationHooks {
	private StructureGenerationHooks() {
	}

	public static void onStructurePlacedInChunk(WorldGenLevel worldGenLevel, StructureStart structureStart) {
		if (!structureStart.isValid()) {
			return;
		}

		Level level = worldGenLevel.getLevel();
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}

		Structure structure = structureStart.getStructure();
		Optional<ResourceKey<Structure>> structureKey = serverLevel.registryAccess()
				.lookupOrThrow(Registries.STRUCTURE)
				.getResourceKey(structure);
		if (structureKey.isEmpty() || !StructureTerritoryRegistry.INSTANCE.shouldTrack(structureKey.get())) {
			return;
		}

		long instanceKey = StructureInstanceKeys.compute(
				structureKey.get(),
				structureStart.getChunkPos(),
				structureStart.getReferences()
		);

		WorldRegionData data = WorldRegionData.get(serverLevel);
		if (!data.tryMarkStructureInstanceRegistered(instanceKey)) {
			return;
		}

		StructureBounds bounds = StructureBoundsHelper.fromBoundingBox(instanceKey, structureStart.getBoundingBox());
		RegionManager.registerStructure(serverLevel, bounds);
		PlayerBlockStatus.LOGGER.info(
				"Registered generated structure {} at {} ({})",
				structureKey.get().location(),
				structureStart.getChunkPos(),
				bounds.cornerA()
		);
	}
}
