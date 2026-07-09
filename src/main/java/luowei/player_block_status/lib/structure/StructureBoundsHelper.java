package luowei.player_block_status.lib.structure;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import luowei.player_block_status.lib.chunk.StructureBounds;

/**
 * 从世界生成管线中的结构包围盒构造 {@link StructureBounds}。
 */
public final class StructureBoundsHelper {
	private StructureBoundsHelper() {
	}

	public static StructureBounds fromBoundingBox(long instanceKey, BoundingBox boundingBox) {
		UUID id = UUID.nameUUIDFromBytes(("pbs-structure:" + instanceKey).getBytes(StandardCharsets.UTF_8));
		BlockPos cornerA = new BlockPos(boundingBox.minX(), boundingBox.minY(), boundingBox.minZ());
		BlockPos cornerB = new BlockPos(boundingBox.maxX(), boundingBox.maxY(), boundingBox.maxZ());
		return new StructureBounds(id, cornerA, cornerB, List.of());
	}
}
