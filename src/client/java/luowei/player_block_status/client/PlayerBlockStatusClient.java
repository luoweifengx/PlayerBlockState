package luowei.player_block_status.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import luowei.player_block_status.lib.net.TerritoryEnterPrefsPayload;

public class PlayerBlockStatusClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		TerritoryEnterClientConfig.get();
		ClientPlayNetworking.registerGlobalReceiver(TerritoryEnterPrefsPayload.TYPE, (payload, context) ->
				context.client().execute(() -> TerritoryEnterClientConfig.get()
						.apply(payload.ownEnterMessage(), payload.infoType())));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (!ClientPlayNetworking.canSend(TerritoryEnterPrefsPayload.TYPE)) {
				return;
			}
			TerritoryEnterClientConfig config = TerritoryEnterClientConfig.reload();
			ClientPlayNetworking.send(new TerritoryEnterPrefsPayload(
					config.ownTerritoryEnterMessage(),
					config.enterMessageInfoType()
			));
		}));
	}
}
