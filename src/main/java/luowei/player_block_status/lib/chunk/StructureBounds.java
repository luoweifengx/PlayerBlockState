package luowei.player_block_status.lib.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * 结构生成时记录的两个顶点及内部方块列表。
 * 若有玩家在其中放置方块，则将所有方块归属该玩家并销毁此记录。
 */
public record StructureBounds(
		UUID id,
		BlockPos cornerA,
		BlockPos cornerB,
		List<BlockPos> blocks
) {
	public static final Codec<StructureBounds> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(StructureBounds::id),
			BlockPos.CODEC.fieldOf("corner_a").forGetter(StructureBounds::cornerA),
			BlockPos.CODEC.fieldOf("corner_b").forGetter(StructureBounds::cornerB),
			BlockPos.CODEC.listOf().fieldOf("blocks").forGetter(StructureBounds::blocks)
	).apply(instance, StructureBounds::new));

	public StructureBounds {
		blocks = List.copyOf(blocks);
	}

	public boolean contains(BlockPos pos) {
		int minX = Math.min(cornerA.getX(), cornerB.getX());
		int minY = Math.min(cornerA.getY(), cornerB.getY());
		int minZ = Math.min(cornerA.getZ(), cornerB.getZ());
		int maxX = Math.max(cornerA.getX(), cornerB.getX());
		int maxY = Math.max(cornerA.getY(), cornerB.getY());
		int maxZ = Math.max(cornerA.getZ(), cornerB.getZ());
		return pos.getX() >= minX && pos.getX() <= maxX
				&& pos.getY() >= minY && pos.getY() <= maxY
				&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	public List<BlockPos> resolvedBlocks() {
		if (!blocks.isEmpty()) {
			return blocks;
		}

		List<BlockPos> resolved = new ArrayList<>();
		int minX = Math.min(cornerA.getX(), cornerB.getX());
		int minY = Math.min(cornerA.getY(), cornerB.getY());
		int minZ = Math.min(cornerA.getZ(), cornerB.getZ());
		int maxX = Math.max(cornerA.getX(), cornerB.getX());
		int maxY = Math.max(cornerA.getY(), cornerB.getY());
		int maxZ = Math.max(cornerA.getZ(), cornerB.getZ());

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					resolved.add(new BlockPos(x, y, z));
				}
			}
		}
		return resolved;
	}
}
