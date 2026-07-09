package luowei.player_block_status.lib.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.ChunkTerritoryData;
import luowei.player_block_status.lib.chunk.RegionManager;
import luowei.player_block_status.lib.chunk.StructureBounds;
import luowei.player_block_status.lib.chunk.WorldRegionData;
import luowei.player_block_status.lib.debug.ChunkDebugMapRenderer;

/**
 * 对外公开 API，供其他模组注册回调与查询区块信息。
 */
public final class PlayerBlockStatusLib {
	private static OrganizationProvider organizationProvider = OrganizationProvider.NONE;
	private static SafeBiomeChecker safeBiomeChecker = SafeBiomeChecker.NONE;

	private PlayerBlockStatusLib() {
	}

	public static void setOrganizationProvider(OrganizationProvider provider) {
		organizationProvider = provider == null ? OrganizationProvider.NONE : provider;
	}

	public static void setSafeBiomeChecker(SafeBiomeChecker checker) {
		safeBiomeChecker = checker == null ? SafeBiomeChecker.NONE : checker;
	}

	public static OrganizationProvider getOrganizationProvider() {
		return organizationProvider;
	}

	public static SafeBiomeChecker getSafeBiomeChecker() {
		return safeBiomeChecker;
	}

	public static void registerStructure(ServerLevel level, StructureBounds bounds) {
		RegionManager.registerStructure(level, bounds);
	}

	public static void transferPlayerToOrg(ServerLevel level, UUID playerId, UUID orgId) {
		RegionManager.transferPlayerToOrg(level, playerId, orgId);
	}

	public static void remapOrganization(ServerLevel level, UUID from, UUID to) {
		RegionManager.remapOrganization(level, from, to);
	}

	public static Optional<ChunkTerritoryData> queryChunk(ServerLevel level, ChunkPos chunkPos) {
		return WorldRegionData.get(level).queryChunk(chunkPos);
	}

	public static Optional<ChunkState> queryChunkState(ServerLevel level, ChunkPos chunkPos) {
		return queryChunk(level, chunkPos).map(ChunkTerritoryData::getState);
	}

	public static Map<Long, ChunkTerritoryData> queryAllChunks(ServerLevel level) {
		return WorldRegionData.get(level).getAllChunks();
	}

	public static Set<Long> queryEntityChunks(ServerLevel level, UUID entityId) {
		return WorldRegionData.get(level).getEntityChunkIndex().getChunks(entityId);
	}

	public static Optional<UUID> queryPlayerOrganization(net.minecraft.server.MinecraftServer server, UUID playerId) {
		return luowei.player_block_status.lib.org.OrganizationService.getOrganizationId(server, playerId);
	}

	public static Optional<luowei.player_block_status.lib.org.OrganizationRecord> queryOrganization(
			net.minecraft.server.MinecraftServer server,
			UUID orgId
	) {
		return luowei.player_block_status.lib.org.OrganizationService.getOrganization(server, orgId);
	}

	public static Path exportDebugMap(ServerLevel level, ChunkPos center, int radiusChunks, Path outputPath) {
		return ChunkDebugMapRenderer.render(level, center, radiusChunks, outputPath);
	}

	public static Path exportDebugMap(ServerLevel level, Path outputPath) {
		return ChunkDebugMapRenderer.renderFull(level, outputPath);
	}
}
