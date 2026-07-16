package luowei.player_block_status.lib.event;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.api.PlayerBlockStatusLib;
import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.RegionManager;
import luowei.player_block_status.lib.chunk.StructureClaimProcessor;
import luowei.player_block_status.lib.chunk.TerritoryConfig;
import luowei.player_block_status.lib.debug.ChunkDebugMapRenderer;
import luowei.player_block_status.lib.debug.MapExportTrace;
import luowei.player_block_status.lib.org.OrganizationCommands;

/**
 * 注册方块、停留、死亡与每日刷新事件。
 */
public final class TerritoryEventHandler {
	private static final int MAP_RADIUS_MAX = 128;
	private static final ExecutorService MAP_EXPORT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "pbs-map-export");
		thread.setDaemon(true);
		return thread;
	});
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

			StructureClaimProcessor.tick(serverLevel);

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
							.then(Commands.literal("spawn")
									.executes(context -> scheduleMapExport(
											context.getSource(),
											context.getSource().getLevel(),
											new ChunkPos(context.getSource().getLevel().getSharedSpawnPos()),
											32,
											"spawn"
									))
									.then(Commands.argument("radius", IntegerArgumentType.integer(1, MAP_RADIUS_MAX))
											.executes(context -> scheduleMapExport(
													context.getSource(),
													context.getSource().getLevel(),
													new ChunkPos(context.getSource().getLevel().getSharedSpawnPos()),
													IntegerArgumentType.getInteger(context, "radius"),
													"spawn"
											))))
							.then(Commands.argument("player", EntityArgument.player())
									.then(Commands.argument("radius", IntegerArgumentType.integer(1, MAP_RADIUS_MAX))
											.executes(context -> {
												ServerPlayer target = EntityArgument.getPlayer(context, "player");
												return scheduleMapExport(
														context.getSource(),
														target.serverLevel(),
														target.chunkPosition(),
														IntegerArgumentType.getInteger(context, "radius"),
														target.getGameProfile().getName()
												);
											}))))
					.then(Commands.literal("legend")
							.requires(source -> source.hasPermission(2))
							.executes(context -> {
								context.getSource().sendSuccess(() -> Component.literal(ChunkDebugMapRenderer.legendText()), false);
								return 1;
							})));
		});

		PlayerBlockStatus.LOGGER.info("Territory event handler registered");
	}

	private static int scheduleMapExport(
			CommandSourceStack source,
			ServerLevel level,
			ChunkPos center,
			int radius,
			String label
	) {
		Path path = level.getServer().getWorldPath(LevelResource.ROOT)
				.resolve("pbs-chunk-map-" + level.dimension().location().getPath() + "-" + sanitizeFileLabel(label) + ".png");

		MapExportTrace trace = new MapExportTrace(label);
		trace.step("command accepted, center=%s radius=%d output=%s", center, radius, path);

		source.sendSuccess(
				() -> Component.literal("Exporting chunk map (radius " + radius + ", center " + center + ")..."),
				false
		);

		trace.step("chat feedback sent, queueing server task");

		level.getServer().execute(() -> {
			trace.step("server task running on %s", Thread.currentThread().getName());
			Map<Long, ChunkState> snapshot = ChunkDebugMapRenderer.collectChunkStates(level, center, radius, trace);
			trace.step("snapshot ready (%d chunks), submitting PNG render to pbs-map-export", snapshot.size());
			CompletableFuture.runAsync(
					() -> {
						trace.step("PNG render started on %s", Thread.currentThread().getName());
						ChunkDebugMapRenderer.renderFromStates(snapshot, center, radius, path, trace);
						trace.step("PNG render finished on worker thread");
					},
					MAP_EXPORT_EXECUTOR
			).whenComplete((ignored, error) -> level.getServer().execute(() -> {
				trace.step("completion callback on %s", Thread.currentThread().getName());
				if (error != null) {
					PlayerBlockStatus.LOGGER.error("[pbs map] export failed after {}ms", trace.elapsedMillis(), error);
					source.sendFailure(Component.literal("Map export failed: " + error.getMessage()));
					return;
				}
				trace.step("export complete, total elapsed {}ms", trace.elapsedMillis());
				source.sendSuccess(
						() -> Component.literal("Chunk map exported to " + path.toAbsolutePath()),
						true
				);
			}));
		});

		return 1;
	}

	private static String sanitizeFileLabel(String label) {
		return label.replaceAll("[\\\\/:*?\"<>|]", "_");
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
