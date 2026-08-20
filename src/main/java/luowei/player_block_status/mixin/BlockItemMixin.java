package luowei.player_block_status.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.event.TerritoryEventHandler;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "placeBlock", at = @At("RETURN"))
	private void playerBlockStatus$afterPlaceBlock(
			BlockPlaceContext context,
			BlockState state,
			CallbackInfoReturnable<Boolean> cir
	) {
		if (!Boolean.TRUE.equals(cir.getReturnValue())) {
			return;
		}

		Level level = context.getLevel();
		if (level.isClientSide() || !(context.getPlayer() instanceof ServerPlayer player)) {
			return;
		}

		// getClickedPos() is already the placed cell (vanilla applies clicked-face offset).
		BlockPos placedPos = context.getClickedPos();
		PlayerBlockStatus.LOGGER.debug(
				"[pbs place] pos={} replacing={} face={}",
				placedPos,
				context.replacingClickedOnBlock(),
				context.getClickedFace()
		);

		TerritoryEventHandler.onBlockPlaced(player, placedPos);
	}
}
