package luowei.player_block_status.lib.structure;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * 为单个结构实例生成稳定去重键（结构类型 + 锚点区块 + 引用计数）。
 */
public final class StructureInstanceKeys {
	private StructureInstanceKeys() {
	}

	public static long compute(ResourceKey<Structure> structureKey, ChunkPos originChunk, int references) {
		ResourceLocation id = structureKey.location();
		long hash = id.hashCode();
		hash = 31 * hash + id.getNamespace().hashCode();
		hash = 31 * hash + id.getPath().hashCode();
		hash = 31 * hash + originChunk.toLong();
		hash = 31 * hash + references;
		return hash;
	}
}
