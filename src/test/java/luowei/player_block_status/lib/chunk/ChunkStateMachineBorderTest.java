package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

class ChunkStateMachineBorderTest {
	private static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		// ChunkPos 静态初始化会碰到注册表；只做 JVM bootstrap，不启动游戏窗口。
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	@AfterEach
	void restoreTerritoryConfigDefaults() {
		TerritoryConfig.hostileBorderExtensionChunks = 2;
	}

	@Test
	void interiorOccupiedSurroundedByOwnOccupiedStaysOccupied() {
		Map<Long, ChunkState> result = deriveOwnOccupiedSquare();

		assertEquals(ChunkState.OCCUPIED, result.get(key(1, 1)));
	}

	@Test
	void occupiedEdgeAdjacentToNonOwnBecomesBorder() {
		Map<Long, ChunkState> result = deriveOwnOccupiedSquare();

		assertEquals(ChunkState.BORDER, result.get(key(0, 0)));
		assertEquals(ChunkState.BORDER, result.get(key(1, 0)));
		assertEquals(ChunkState.BORDER, result.get(key(2, 0)));
		assertEquals(ChunkState.BORDER, result.get(key(0, 1)));
		assertEquals(ChunkState.BORDER, result.get(key(2, 1)));
		assertEquals(ChunkState.BORDER, result.get(key(0, 2)));
		assertEquals(ChunkState.BORDER, result.get(key(1, 2)));
		assertEquals(ChunkState.BORDER, result.get(key(2, 2)));
	}

	@Test
	void naturalWithinChebyshevTwoOfBorderBecomesHostileBorder() {
		Map<Long, ChunkState> base = occupiedSquare();
		base.put(key(4, 1), ChunkState.NATURAL);
		base.put(key(3, 1), ChunkState.NATURAL);
		base.put(key(5, 1), ChunkState.NATURAL);

		Map<Long, ChunkState> result = ChunkStateMachine.deriveBorderStatesFromBaseStates(
				base,
				occupiedSquareOrgs(ORG_A),
				Map.of()
		);

		assertEquals(ChunkState.HOSTILE_BORDER, result.get(key(3, 1)));
		assertEquals(ChunkState.HOSTILE_BORDER, result.get(key(4, 1)));
		assertEquals(ChunkState.NATURAL, result.get(key(5, 1)));
		assertEquals(ChunkState.HOSTILE_BORDER, result.get(key(-2, 1)));
	}

	@Test
	void hostileBorderDoesNotOverwriteForeignOccupiedOrBorder() {
		Map<Long, ChunkState> base = occupiedSquare();
		Map<Long, UUID> orgs = occupiedSquareOrgs(ORG_A);
		for (int x = 3; x <= 5; x++) {
			for (int z = 0; z <= 2; z++) {
				base.put(key(x, z), ChunkState.OCCUPIED);
				orgs.put(key(x, z), ORG_B);
			}
		}

		Map<Long, ChunkState> result = ChunkStateMachine.deriveBorderStatesFromBaseStates(base, orgs, Map.of());

		assertEquals(ChunkState.OCCUPIED, result.get(key(4, 1)));
		assertEquals(ChunkState.BORDER, result.get(key(3, 1)));
		assertNotEquals(ChunkState.HOSTILE_BORDER, result.get(key(4, 1)));
		assertNotEquals(ChunkState.HOSTILE_BORDER, result.get(key(3, 1)));
	}

	@Test
	void hostileBorderDoesNotOverwriteDemon() {
		Map<Long, ChunkState> base = occupiedSquare();
		base.put(key(4, 1), ChunkState.DEMON);

		Map<Long, ChunkState> result = ChunkStateMachine.deriveBorderStatesFromBaseStates(
				base,
				occupiedSquareOrgs(ORG_A),
				Map.of()
		);

		assertEquals(ChunkState.DEMON, result.get(key(4, 1)));
	}

	@Test
	void demonInContextIsNotOverwrittenByHostileBorder() {
		Map<Long, ChunkState> result = ChunkStateMachine.deriveBorderStatesFromBaseStates(
				occupiedSquare(),
				occupiedSquareOrgs(ORG_A),
				Map.of(key(4, 1), new ChunkStateMachine.NeighborChunkView(ChunkState.DEMON, null))
		);

		assertNotEquals(ChunkState.HOSTILE_BORDER, result.get(key(4, 1)));
	}

	private static Map<Long, ChunkState> deriveOwnOccupiedSquare() {
		return ChunkStateMachine.deriveBorderStatesFromBaseStates(
				occupiedSquare(),
				occupiedSquareOrgs(ORG_A),
				Map.of()
		);
	}

	private static Map<Long, ChunkState> occupiedSquare() {
		Map<Long, ChunkState> base = new HashMap<>();
		for (int x = 0; x < 3; x++) {
			for (int z = 0; z < 3; z++) {
				base.put(key(x, z), ChunkState.OCCUPIED);
			}
		}
		return base;
	}

	private static Map<Long, UUID> occupiedSquareOrgs(UUID org) {
		Map<Long, UUID> orgs = new HashMap<>();
		for (int x = 0; x < 3; x++) {
			for (int z = 0; z < 3; z++) {
				orgs.put(key(x, z), org);
			}
		}
		return orgs;
	}

	private static long key(int x, int z) {
		return ChunkPos.asLong(x, z);
	}
}
