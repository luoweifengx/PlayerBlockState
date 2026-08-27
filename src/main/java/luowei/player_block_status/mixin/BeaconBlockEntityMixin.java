package luowei.player_block_status.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import luowei.player_block_status.lib.chunk.DemonChunks;

/**
 * 信标 tick 末尾上报金字塔等级。levels=0 视为停止供奉。
 */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconBlockEntityMixin {
	@Shadow
	int levels;

	@Inject(
			method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BeaconBlockEntity;)V",
			at = @At("TAIL")
	)
	private static void playerBlockStatus$reportBeaconOffering(
			Level level,
			BlockPos pos,
			BlockState state,
			BeaconBlockEntity blockEntity,
			CallbackInfo ci
	) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		int levels = ((BeaconBlockEntityMixin) (Object) blockEntity).levels;
		DemonChunks.reportBeacon(serverLevel, pos, levels);
	}
}
