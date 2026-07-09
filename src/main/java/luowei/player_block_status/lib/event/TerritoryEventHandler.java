package luowei.player_block_status.lib.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.api.PlayerBlockStatusLib;
import luowei.player_block_status.lib.chunk.RegionManager;
import luowei.player_block_status.lib.chunk.TerritoryConfig;
import luowei.player_block_status.lib.debug.ChunkDebugMapRenderer;
import luowei.player_block_status.lib.org.OrganizationCommands;

/**
 * 注册方块、停留、死亡与每日刷新事件。
 */
public final class TerritoryEventHandler {
	private static final Map<UUID, PlayerStayTracker> STAY_TRACKERS = new HashMap<>();

	private TerritoryEventHandler() {
	}

	public static void register() {
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel serverLevel) {
				RegionManager.onBlockRemoved(serverLevel, pos);
			}
		});

		ServerTickEvents.END_WORLD_TICK.register(level -> {
			if (!(level instanceof ServerLevel serverLevel)) {
				return;
			}

			for (ServerPlayer player : serverLevel.players()) {
				tickPlayerStay(serverLevel, player);
			}

			RegionManager.tickDaily(serverLevel,
					PlayerBlockStatusLib.getOrganizationProvider(),
					PlayerBlockStatusLib.getSafeBiomeChecker());
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("pbs")
					.then(OrganizationCommands.buildOrgNode())
					.then(Commands.literal("query")
							.requires(source -> source.hasPermission(2))
							.then(Commands.argument("pos", BlockPosArgument.blockPos())
									.executes(context -> {
										BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
										ServerLevel level = context.getSource().getLevel();
										ChunkPos chunkPos = new ChunkPos(pos);
										var info = luowei.player_block_status.lib.debug.ChunkQueryUtil.query(level, chunkPos);
										context.getSource().sendSuccess(() -> Component.literal(info.toString()), false);
										return 1;
									})))
					.then(Commands.literal("map")
							.requires(source -> source.hasPermission(2))
							.executes(context -> exportMap(context.getSource(), context.getSource().getLevel(), 32))
							.then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 512))
									.executes(context -> exportMap(context.getSource(), context.getSource().getLevel(),
											com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius")))))
					.then(Commands.literal("legend")
							.requires(source -> source.hasPermission(2))
							.executes(context -> {
								context.getSource().sendSuccess(() -> Component.literal(ChunkDebugMapRenderer.legendText()), false);
								return 1;
							})));
		});

		PlayerBlockStatus.LOGGER.info("Territory event handler registered");
	}

	private static int exportMap(net.minecraft.commands.CommandSourceStack source, ServerLevel level, int radius) {
		var center = level.getSharedSpawnPos();
		var path = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.resolve("pbs-chunk-map-" + level.dimension().location().getPath() + ".png");
		PlayerBlockStatusLib.exportDebugMap(level, new ChunkPos(center), radius, path);
		source.sendSuccess(() -> Component.literal("Chunk map exported to " + path.toAbsolutePath()), true);
		return 1;
	}

	private static void tickPlayerStay(ServerLevel level, ServerPlayer player) {
		PlayerStayTracker tracker = STAY_TRACKERS.computeIfAbsent(player.getUUID(), id -> new PlayerStayTracker());
		ChunkPos currentChunk = player.chunkPosition();

		if (!currentChunk.equals(tracker.lastChunk)) {
			tracker.lastChunk = currentChunk;
			tracker.tickCounter = 0;
			return;
		}

		tracker.tickCounter++;
		if (tracker.tickCounter >= TerritoryConfig.stayTickInterval) {
			tracker.tickCounter = 0;
			RegionManager.onPlayerStay(level, player, currentChunk,
					PlayerBlockStatusLib.getOrganizationProvider());
		}
	}

	public static void onPlayerDeath(ServerPlayer player) {
		STAY_TRACKERS.remove(player.getUUID());
		RegionManager.onPlayerDeath(player.serverLevel(), player,
				PlayerBlockStatusLib.getOrganizationProvider());
	}

	public static void onBlockPlaced(ServerPlayer player, BlockPos pos) {
		RegionManager.onBlockPlaced(player.serverLevel(), pos, player, PlayerBlockStatusLib.getOrganizationProvider());
	}

	private static final class PlayerStayTracker {
		private ChunkPos lastChunk = new ChunkPos(0, 0);
		private int tickCounter;
	}
}
