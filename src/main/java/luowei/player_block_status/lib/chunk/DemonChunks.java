package luowei.player_block_status.lib.chunk;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.advancement.TerritoryAdvancements;
import luowei.player_block_status.lib.api.BeaconOfferingSnapshot;
import luowei.player_block_status.lib.api.PlayerBlockStatusLib;

/**
 * 恶魔区块入口：传送门生成、日更扩散、信标供奉效果、地狱门破坏。
 */
public final class DemonChunks {
	private DemonChunks() {
	}

	public static void onBeaconOfferingChanged(
			MinecraftServer server,
			BeaconOfferingSnapshot previous,
			BeaconOfferingSnapshot current
	) {
		DemonChunkRules.RecomputeResult result = DemonChunkRules.recompute(
				current.operatingCount(),
				current.maxLevel(),
				previous.operatingCount(),
				previous.maxLevel()
		);
		DemonChunkWorldData.get(server).setFlags(result.flags());
		if (result.clearAllDemonChunks()) {
			clearAll(server);
		}
		if (result.fireReset()) {
			destroyAllNetherPortals(server);
		}
	}

	public static void reportBeacon(ServerLevel level, BlockPos pos, int beaconLevel) {
		if (level == null || pos == null || level.getServer() == null) {
			return;
		}
		DemonChunkWorldData.BeaconChange change = DemonChunkWorldData.get(level.getServer())
				.updateBeacon(level.dimension(), pos, beaconLevel);
		if (change.changed()) {
			PlayerBlockStatusLib.notifyBeaconOfferingListeners(level.getServer(), change.previous(), change.current());
		}
	}

	public static void removeBeacon(ServerLevel level, BlockPos pos) {
		reportBeacon(level, pos, 0);
	}

	public static void onPortalOpened(ServerLevel level, BlockPos pos, BlockState state) {
		if (level == null || pos == null || state == null || level.getServer() == null) {
			return;
		}
		if (state.is(Blocks.NETHER_PORTAL)) {
			DemonChunkWorldData.get(level.getServer()).trackNetherPortal(level.dimension(), pos);
			TerritoryAdvancements.onFirstNetherPortalOpened(level.getServer());
		}
		if (!DemonChunkRules.shouldCreateDemonFromPortal(
				DemonChunkWorldData.get(level.getServer()).isGenerationForbidden()
		)) {
			return;
		}
		WorldRegionData.get(level).convertToDemon(new ChunkPos(pos).toLong());
	}

	public static void onNetherPortalRemoved(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}
		DemonChunkWorldData.get(level.getServer()).untrackNetherPortal(level.dimension(), pos);
	}

	public static void spreadForDay(ServerLevel level) {
		DemonChunkWorldData worldData = DemonChunkWorldData.get(level.getServer());
		if (!worldData.isSpreadingEnabled() || worldData.isGenerationForbidden()) {
			return;
		}
		WorldRegionData data = WorldRegionData.get(level);
		Set<Long> demonKeys = data.getDemonChunkKeys();
		if (demonKeys.isEmpty()) {
			return;
		}
		Random random = new Random(level.random.nextLong());
		DemonChunkSpread.spreadOnce(
				demonKeys,
				key -> {
					ChunkTerritoryData chunk = data.getChunk(key);
					return chunk != null && chunk.getState().isDemon();
				},
				data::convertToDemon,
				worldData.getSpreadProbability(),
				true,
				random
		);
	}

	public static void clearAll(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			WorldRegionData.get(level).clearAllDemonChunks();
		}
		PlayerBlockStatus.LOGGER.info("[pbs demon] cleared all demon chunks (level-3 beacon)");
	}

	public static void destroyAllNetherPortals(MinecraftServer server) {
		DemonChunkWorldData worldData = DemonChunkWorldData.get(server);
		Set<DemonChunkWorldData.StoredPos> tracked = worldData.copyNetherPortals();
		int destroyed = 0;
		for (ServerLevel level : server.getAllLevels()) {
			destroyed += destroyNetherPortalsIn(level, tracked);
		}
		worldData.clearNetherPortals();
		PlayerBlockStatus.LOGGER.info("[pbs demon] destroyed {} nether portal blocks (beacon count hit 0)", destroyed);
	}

	public static void trackNetherPortalsInChunk(ServerLevel level, LevelChunk chunk) {
		if (level == null || chunk == null) {
			return;
		}
		level.getPoiManager()
				.getInChunk(type -> type.is(PoiTypes.NETHER_PORTAL), chunk.getPos(), PoiManager.Occupancy.ANY)
				.forEach(record -> DemonChunkWorldData.get(level.getServer())
						.trackNetherPortal(level.dimension(), record.getPos()));
	}

	public static void validateBeaconsInChunk(ServerLevel level, ChunkPos chunkPos) {
		if (level == null || chunkPos == null) {
			return;
		}
		DemonChunkWorldData worldData = DemonChunkWorldData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		for (DemonChunkWorldData.StoredPos beacon : worldData.copyBeacons()) {
			if (!dimension.equals(beacon.dimension())) {
				continue;
			}
			if ((beacon.x() >> 4) != chunkPos.x || (beacon.z() >> 4) != chunkPos.z) {
				continue;
			}
			if (!level.getBlockState(beacon.blockPos()).is(Blocks.BEACON)) {
				removeBeacon(level, beacon.blockPos());
			}
		}
	}

	private static int destroyNetherPortalsIn(ServerLevel level, Set<DemonChunkWorldData.StoredPos> tracked) {
		Set<BlockPos> positions = new HashSet<>();
		ResourceLocation dimensionId = level.dimension().location();
		for (DemonChunkWorldData.StoredPos stored : tracked) {
			if (dimensionId.equals(stored.dimensionLocation())) {
				positions.add(stored.blockPos());
			}
		}
		collectLoadedPortalPois(level, positions);

		int destroyed = 0;
		for (BlockPos pos : positions) {
			if (level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
				if (level.removeBlock(pos, false)) {
					destroyed++;
				}
			}
		}
		return destroyed;
	}

	private static void collectLoadedPortalPois(ServerLevel level, Set<BlockPos> out) {
		PoiManager pois = level.getPoiManager();
		Set<ChunkPos> scan = new HashSet<>();
		scan.add(new ChunkPos(level.getSharedSpawnPos()));
		for (ServerPlayer player : level.players()) {
			scan.add(player.chunkPosition());
		}
		int view = Math.max(8, level.getServer().getPlayerList().getViewDistance());
		Set<ChunkPos> expanded = new HashSet<>();
		for (ChunkPos center : scan) {
			for (int dx = -view; dx <= view; dx++) {
				for (int dz = -view; dz <= view; dz++) {
					expanded.add(new ChunkPos(center.x + dx, center.z + dz));
				}
			}
		}
		for (ChunkPos chunkPos : expanded) {
			if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
				continue;
			}
			pois.getInChunk(type -> type.is(PoiTypes.NETHER_PORTAL), chunkPos, PoiManager.Occupancy.ANY)
					.forEach(record -> out.add(record.getPos()));
		}
	}
}
