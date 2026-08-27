package luowei.player_block_status.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import luowei.player_block_status.lib.structure.StructureGenerationHooks;

/**
 * 结构 placeInChunk 捕获窗口内的 WorldGenRegion.setBlock 钩子。
 * <p>
 * 暂时为主捕获路径（{@link ChunkSetBlockStateMixin} 已从 mixins.json 注销）。
 * 重复坐标由 {@link luowei.player_block_status.lib.structure.StructureGenerationHooks} 的 HashSet 去重。
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
	@Inject(method = "setBlock", at = @At("RETURN"))
	private void playerBlockStatus$markStructureBlock(
			BlockPos pos,
			BlockState state,
			int flags,
			int recursionLeft,
			CallbackInfoReturnable<Boolean> cir
	) {
		if (!Boolean.TRUE.equals(cir.getReturnValue())) {
			return;
		}
		if (!StructureGenerationHooks.isCapturingStructureBlocks()) {
			return;
		}
		if (state == null || state.isAir() || state.is(Blocks.STRUCTURE_VOID)) {
			return;
		}
		StructureGenerationHooks.markTemplateBlock(pos);
	}
}
