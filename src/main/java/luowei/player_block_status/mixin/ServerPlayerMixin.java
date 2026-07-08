package luowei.player_block_status.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.event.TerritoryEventHandler;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "die", at = @At("HEAD"))
	private void playerBlockStatus$onDeath(net.minecraft.world.damagesource.DamageSource damageSource, CallbackInfo ci) {
		TerritoryEventHandler.onPlayerDeath((ServerPlayer) (Object) this);
	}
}
