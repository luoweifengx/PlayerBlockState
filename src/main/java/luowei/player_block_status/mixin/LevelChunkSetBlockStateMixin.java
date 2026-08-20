package luowei.player_block_status.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import luowei.player_block_status.lib.api.PlayerBlockStatusLib;
import luowei.player_block_status.lib.chunk.ChunkTerritoryAccess;
import luowei.player_block_status.lib.chunk.ChunkTerritoryData;
import luowei.player_block_status.lib.structure.StructureGenerationHooks;

/**
 * 运行中世界删分入口：原版 {@link LevelChunk#setBlockState}（挖掘、爆炸、活塞、流体、火焰、重力等最终都汇到这里）。
 * <p>
 * 不枚举破坏来源。不处理 {@link net.minecraft.world.level.chunk.ProtoChunk}（世界生成也会写方块）。
 * 结构捕获窗口内不删分。无领土 attachment 立即返回，不 getOrCreate。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSetBlockStateMixin {
	@Shadow
	public abstract Level getLevel();

	@Inject(
			method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
			at = @At("RETURN")
	)
	private void playerBlockStatus$notifyTrackedBlockRemoved(
			BlockPos pos,
			BlockState state,
			int flags,
			CallbackInfoReturnable<BlockState> cir
	) {
		BlockState oldState = cir.getReturnValue();
		if (oldState == null) {
			return;
		}
		if (StructureGenerationHooks.isCapturingStructureBlocks()) {
			return;
		}
		if (!(getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		if (state == null || oldState.equals(state)) {
			return;
		}

		ChunkTerritoryData territory = ChunkTerritoryAccess.getIfPresent((LevelChunk) (Object) this);
		if (territory == null || territory.getPlacedBlockOwner(pos) == null) {
			return;
		}

		PlayerBlockStatusLib.notifyTrackedBlockRemoved(serverLevel, pos);
	}
}
