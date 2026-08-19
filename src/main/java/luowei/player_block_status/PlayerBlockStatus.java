package luowei.player_block_status;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import luowei.player_block_status.lib.api.PlayerBlockStatusLib;
import luowei.player_block_status.lib.api.StructureTerritoryContributor;
import luowei.player_block_status.lib.chunk.TerritoryAttachments;
import luowei.player_block_status.lib.event.TerritoryEventHandler;
import luowei.player_block_status.lib.org.InternalOrganizationProvider;
import luowei.player_block_status.lib.org.OrganizationData;
import luowei.player_block_status.lib.org.OrganizationService;
import luowei.player_block_status.lib.structure.StructureTerritoryRegistry;

public class PlayerBlockStatus implements ModInitializer {
	public static final String MOD_ID = "player-block-status";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		TerritoryAttachments.register();
		loadStructureTerritoryContributors();
		TerritoryEventHandler.register();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			PlayerBlockStatusLib.setOrganizationProvider(InternalOrganizationProvider.INSTANCE);
			OrganizationData.get(server).reconcilePollList();
			LOGGER.info("Internal organization provider registered");
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			OrganizationService.ensureSoloPlayerInPollList(server, handler.getPlayer().getUUID());
		});
		LOGGER.info("Player Block Status lib initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	private static void loadStructureTerritoryContributors() {
		FabricLoader.getInstance()
				.getEntrypointContainers("player-block-status:structure_territory", StructureTerritoryContributor.class)
				.forEach(container -> {
					try {
						container.getEntrypoint().registerStructureTerritory(StructureTerritoryRegistry.INSTANCE);
						LOGGER.info("Loaded structure territory contributor from {}", container.getProvider().getMetadata().getId());
					} catch (Exception exception) {
						LOGGER.error(
								"Failed to load structure territory contributor from {}",
								container.getProvider().getMetadata().getId(),
								exception
						);
					}
				});
	}
}
