package luowei.player_block_status.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;

import luowei.player_block_status.lib.structure.StructureGenerationHooks;

/**
 * 结构 placeInChunk 捕获窗口内的底层放块钩子。
 * <p>
 * 暂时去掉：未在 {@code player-block-status.mixins.json} 注册；ProtoChunk/LevelChunk 捕获热路径
 * 改由 {@link WorldGenRegionMixin} 承担。保留本类便于日后重新启用。
 * <p>
 * 不能注入抽象的 {@link net.minecraft.world.level.chunk.ChunkAccess#setBlockState}：
 * 无方法体则 {@code @At("RETURN")} 会 Scanned 0 并导致启动崩溃。
 * 因此挂在有实现的 {@link ProtoChunk}（世界生成主路径）与 {@link LevelChunk}
 * （含 ImposterProtoChunk 委托写入）上。
 * <p>
 * 仅入队坐标，禁止 getChunk / attachment。与 {@link WorldGenRegionMixin} 重复坐标由 HashSet 去重。
 * 运行中世界的删分见 {@link LevelChunkSetBlockStateMixin}，不在 ProtoChunk 上查领土数据。
 */
@Mixin({ProtoChunk.class, LevelChunk.class})
public abstract class ChunkSetBlockStateMixin {
	@Inject(
			method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
			at = @At("RETURN")
	)
	private void playerBlockStatus$markStructureBlock(
			BlockPos pos,
			BlockState state,
			int flags,
			CallbackInfoReturnable<BlockState> cir
	) {
		// 写入失败时返回 null
		if (cir.getReturnValue() == null) {
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
