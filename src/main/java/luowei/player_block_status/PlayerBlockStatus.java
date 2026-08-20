package luowei.player_block_status;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import luowei.player_block_status.lib.api.StructureTerritoryContributor;
import luowei.player_block_status.lib.chunk.TerritoryAttachments;
import luowei.player_block_status.lib.chunk.TerritoryConfigFile;
import luowei.player_block_status.lib.event.TerritoryEnterMessagePrefs;
import luowei.player_block_status.lib.event.TerritoryEventHandler;
import luowei.player_block_status.lib.net.TerritoryEnterPrefsPayload;
import luowei.player_block_status.lib.org.EntityDisplayNames;
import luowei.player_block_status.lib.org.OrganizationData;
import luowei.player_block_status.lib.org.OrganizationService;
import luowei.player_block_status.lib.structure.StructureTerritoryRegistry;

public class PlayerBlockStatus implements ModInitializer {
	public static final String MOD_ID = "player-block-status";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		TerritoryConfigFile.load();
		TerritoryAttachments.register();
		registerEnterPrefsNetworking();
		loadStructureTerritoryContributors();
		TerritoryEventHandler.register();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			OrganizationData.get(server).reconcilePollList();
			LOGGER.info("Organization provider chain ready (external empty + internal)");
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			OrganizationService.ensureSoloPlayerInPollList(server, player.getUUID());
			EntityDisplayNames.updatePlayerName(server, player.getUUID(), player.getGameProfile().getName());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				TerritoryEnterMessagePrefs.remove(handler.getPlayer().getUUID()));
		LOGGER.info("Player Block Status lib initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	private static void registerEnterPrefsNetworking() {
		PayloadTypeRegistry.playC2S().register(TerritoryEnterPrefsPayload.TYPE, TerritoryEnterPrefsPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(TerritoryEnterPrefsPayload.TYPE, TerritoryEnterPrefsPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TerritoryEnterPrefsPayload.TYPE, (payload, context) ->
				context.server().execute(() -> {
					try {
						TerritoryEnterMessagePrefs.put(
								context.player().getUUID(),
								new TerritoryEnterMessagePrefs.Settings(
										TerritoryEnterMessagePrefs.requireMessage(payload.ownEnterMessage()),
										TerritoryEnterMessagePrefs.InfoType.fromId(payload.infoType())
								)
						);
					} catch (RuntimeException exception) {
						LOGGER.warn(
								"Ignored invalid enter-message prefs from {}",
								context.player().getGameProfile().getName(),
								exception
						);
					}
				}));
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
