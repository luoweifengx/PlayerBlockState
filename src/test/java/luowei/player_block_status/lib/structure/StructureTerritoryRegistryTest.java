package luowei.player_block_status.lib.structure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;

class StructureTerritoryRegistryTest {
	@Test
	void defaultBlacklistSkipsLargeVanillaStructures() {
		assertFalse(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("trial_chambers")));
		assertFalse(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("mineshaft")));
		assertFalse(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("mineshaft_mesa")));
		assertFalse(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("ancient_city")));
		assertFalse(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("stronghold")));
		assertFalse(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("fortress")));
		assertFalse(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("bastion_remnant")));
		assertFalse(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("mansion")));
		assertFalse(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("monument")));
	}

	@Test
	void smallerVanillaStructuresRemainTrackedByDefault() {
		assertTrue(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("village_plains")));
		assertTrue(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("desert_pyramid")));
		assertTrue(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("shipwreck")));
		assertTrue(StructureTerritoryRegistry.INSTANCE.shouldTrack(vanilla("pillager_outpost")));
	}

	private static ResourceKey<Structure> vanilla(String path) {
		return ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace(path));
	}
}
