package luowei.player_block_status.lib.structure;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * 结构在世界生成落地时：对 placeInChunk 期间实际写入的非空气方块收集坐标，
 * 经 {@link StructureSentinelWriteQueue} 延后到 Server 线程写入 sentinel 归属。
 * <p>
 * 捕获窗口由 {@link net.minecraft.world.level.levelgen.structure.StructureStart#placeInChunk} 的 begin/end 界定
 *（ThreadLocal {@link #isCapturingStructureBlocks()}）。窗口内钩子：
 * <ul>
 *   <li>主路径：{@link net.minecraft.world.level.chunk.ProtoChunk} /
 *       {@link net.minecraft.world.level.chunk.LevelChunk}{@code #setBlockState}
 *       （不可注入抽象 {@link net.minecraft.world.level.chunk.ChunkAccess}）</li>
 *   <li>双保险：{@link net.minecraft.server.level.WorldGenRegion#setBlock}</li>
 * </ul>
 * 坐标以 {@link HashSet} 去重；仍不覆盖完全绕过上述写入的自定义路径。
 * <p>
 * Worker 线程上禁止 {@code getChunk} / attachment 写入（见 {@link StructureSentinelWriteQueue}）。
 */
public final class StructureGenerationHooks {
	private static final ThreadLocal<CaptureState> CAPTURE = new ThreadLocal<>();

	private StructureGenerationHooks() {
	}

	public static boolean isCapturingStructureBlocks() {
		CaptureState state = CAPTURE.get();
		return state != null && state.active;
	}

	public static void markTemplateBlock(BlockPos pos) {
		CaptureState state = CAPTURE.get();
		if (state == null || !state.active) {
			return;
		}
		state.positions.add(pos.immutable());
	}

	public static void beginStructurePlacement(
			WorldGenLevel worldGenLevel,
			StructureStart structureStart,
			ChunkPos generatingChunk
	) {
		endCapture();
		if (!structureStart.isValid()) {
			logPlaceInChunk("HEAD", "invalid", structureStart.getChunkPos(), generatingChunk, false, 0, "invalid");
			return;
		}

		Level level = worldGenLevel.getLevel();
		if (!(level instanceof ServerLevel serverLevel)) {
			logPlaceInChunk("HEAD", "unknown", structureStart.getChunkPos(), generatingChunk, false, 0, "not-server-level");
			return;
		}

		Structure structure = structureStart.getStructure();
		Optional<ResourceKey<Structure>> structureKey = serverLevel.registryAccess()
				.lookupOrThrow(Registries.STRUCTURE)
				.getResourceKey(structure);
		String structureId = structureKey.map(key -> key.location().toString()).orElse("unregistered");
		if (structureKey.isEmpty() || !StructureTerritoryRegistry.INSTANCE.shouldTrack(structureKey.get())) {
			logPlaceInChunk("HEAD", structureId, structureStart.getChunkPos(), generatingChunk, false, 0, "untracked");
			return;
		}

		CaptureState state = new CaptureState();
		state.active = true;
		state.serverLevel = serverLevel;
		state.structureKey = structureKey.get();
		state.structureStart = structureStart;
		CAPTURE.set(state);
		logPlaceInChunk("HEAD", structureId, structureStart.getChunkPos(), generatingChunk, true, 0, "-");
	}

	public static void onStructurePlacedInChunk(
			WorldGenLevel worldGenLevel,
			StructureStart structureStart,
			ChunkPos generatingChunk
	) {
		CaptureState state = CAPTURE.get();
		try {
			if (state == null || !state.active || state.serverLevel == null) {
				logPlaceInChunk("RETURN", resolveStructureId(worldGenLevel, structureStart), structureStart.getChunkPos(), generatingChunk, false, 0, "inactive");
				return;
			}
			if (structureStart != state.structureStart) {
				logPlaceInChunk(
						"RETURN",
						state.structureKey.location().toString(),
						structureStart.getChunkPos(),
						generatingChunk,
						false,
						state.positions.size(),
						"start-mismatch"
				);
				return;
			}

			long instanceKey = StructureInstanceKeys.compute(
					state.structureKey,
					structureStart.getChunkPos(),
					structureStart.getReferences()
			);

			// Worker 仅入队；实例去重与 markStructureSentinel 在主线程刷写时执行。
			StructureSentinelWriteQueue.enqueue(
					state.serverLevel,
					state.structureKey,
					instanceKey,
					structureStart.getChunkPos(),
					state.positions
			);
			logPlaceInChunk(
					"RETURN",
					state.structureKey.location().toString(),
					structureStart.getChunkPos(),
					generatingChunk,
					true,
					state.positions.size(),
					"enqueued"
			);
		} finally {
			endCapture();
		}
	}

	private static String resolveStructureId(WorldGenLevel worldGenLevel, StructureStart structureStart) {
		Level level = worldGenLevel.getLevel();
		if (!(level instanceof ServerLevel serverLevel)) {
			return "unknown";
		}
		return serverLevel.registryAccess()
				.lookupOrThrow(Registries.STRUCTURE)
				.getResourceKey(structureStart.getStructure())
				.map(key -> key.location().toString())
				.orElse("unregistered");
	}

	private static void logPlaceInChunk(
			String phase,
			String structureId,
			ChunkPos origin,
			ChunkPos generatingChunk,
			boolean capturing,
			int captured,
			String detail
	) {
		PlayerBlockStatus.LOGGER.info(
				"[pbs structure] placeInChunk {} structure={} origin={} generating={} capturing={} captured={} detail={} thread={}",
				phase,
				structureId,
				origin,
				generatingChunk,
				capturing,
				captured,
				detail,
				Thread.currentThread().getName()
		);
	}

	private static void endCapture() {
		CAPTURE.remove();
	}

	private static final class CaptureState {
		private boolean active;
		private ServerLevel serverLevel;
		private ResourceKey<Structure> structureKey;
		private StructureStart structureStart;
		private final Set<BlockPos> positions = new HashSet<>();
	}
}
