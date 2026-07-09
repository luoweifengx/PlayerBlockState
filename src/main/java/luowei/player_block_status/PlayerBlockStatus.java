package luowei.player_block_status;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import luowei.player_block_status.lib.api.PlayerBlockStatusLib;
import luowei.player_block_status.lib.event.TerritoryEventHandler;
import luowei.player_block_status.lib.org.InternalOrganizationProvider;

public class PlayerBlockStatus implements ModInitializer {
	public static final String MOD_ID = "player-block-status";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		TerritoryEventHandler.register();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			PlayerBlockStatusLib.setOrganizationProvider(InternalOrganizationProvider.INSTANCE);
			LOGGER.info("Internal organization provider registered");
		});
		LOGGER.info("Player Block Status lib initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
