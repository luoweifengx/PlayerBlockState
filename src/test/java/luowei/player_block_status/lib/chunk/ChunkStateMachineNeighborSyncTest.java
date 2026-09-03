package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

class ChunkStateMachineNeighborSyncTest {
	private static final UUID ORG = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void cardinalNeighborKeysAreFourNeighborsNotSelf() {
		Set<Long> neighbors = ChunkStateMachine.cardinalNeighborKeys(Set.of(key(0, 0)));

		assertEquals(Set.of(key(-1, 0), key(1, 0), key(0, -1), key(0, 1)), neighbors);
		assertFalse(neighbors.contains(key(0, 0)));
		assertFalse(neighbors.contains(key(1, 1)));
	}

	@Test
	void centerBorderBecomesOccupiedWhenFourCardinalsAreOwnFamily() {
		Map<Long, ChunkStateMachine.NeighborChunkView> views = new HashMap<>();
		views.put(key(0, 0), view(ChunkState.BORDER));
		views.put(key(-1, 0), view(ChunkState.BORDER));
		views.put(key(1, 0), view(ChunkState.OCCUPIED));
		views.put(key(0, -1), view(ChunkState.BORDER));
		views.put(key(0, 1), view(ChunkState.BORDER));

		ChunkState next = ChunkStateMachine.occupiedFamilyFromCardinals(
				views::get,
				key(0, 0),
				ORG,
				ChunkState.BORDER
		);

		assertEquals(ChunkState.OCCUPIED, next);
	}

	@Test
	void centerStaysBorderWhenACardinalIsMissing() {
		Map<Long, ChunkStateMachine.NeighborChunkView> views = new HashMap<>();
		views.put(key(-1, 0), view(ChunkState.BORDER));
		views.put(key(1, 0), view(ChunkState.BORDER));
		views.put(key(0, -1), view(ChunkState.BORDER));

		ChunkState next = ChunkStateMachine.occupiedFamilyFromCardinals(
				views::get,
				key(0, 0),
				ORG,
				ChunkState.BORDER
		);

		assertEquals(ChunkState.BORDER, next);
	}

	@Test
	void naturalIsUnchangedByOccupiedFamilyRule() {
		assertEquals(
				ChunkState.NATURAL,
				ChunkStateMachine.occupiedFamilyFromCardinals(key -> null, key(0, 0), ORG, ChunkState.NATURAL)
		);
	}

	@Test
	void newBorderDemotesCardinalHostileOnly() {
		Map<Long, ChunkStateMachine.NeighborChunkView> views = new HashMap<>();
		views.put(key(0, 0), view(ChunkState.BORDER));
		views.put(key(1, 0), hostile(ChunkState.HOSTILE));
		views.put(key(1, 1), hostile(ChunkState.HOSTILE));
		views.put(key(0, 1), view(ChunkState.NATURAL, null));
		views.put(key(-1, 0), view(ChunkState.DEMON, null));
		views.put(key(0, -1), view(ChunkState.OCCUPIED));
		views.put(key(1, -1), hostile(ChunkState.HOSTILE_BORDER));

		Map<Long, ChunkState> demotes = ChunkStateMachine.cardinalDemotesFromNewBorders(
				views::get,
				Set.of(key(0, 0))
		);

		assertEquals(ChunkState.HOSTILE_BORDER, demotes.get(key(1, 0)));
		assertEquals(1, demotes.size());
		assertFalse(demotes.containsKey(key(1, 1)));
		assertFalse(demotes.containsKey(key(0, 1)));
		assertFalse(demotes.containsKey(key(-1, 0)));
		assertFalse(demotes.containsKey(key(0, -1)));
		assertFalse(demotes.containsKey(key(1, -1)));
	}

	@Test
	void newHostileBorderDemotesCardinalOccupiedKeepingOrgInView() {
		Map<Long, ChunkStateMachine.NeighborChunkView> views = new HashMap<>();
		views.put(key(0, 0), view(ChunkState.HOSTILE_BORDER, null));
		views.put(key(1, 0), view(ChunkState.OCCUPIED));
		views.put(key(-1, 0), view(ChunkState.BORDER));
		views.put(key(0, 1), hostile(ChunkState.HOSTILE));
		views.put(key(1, 1), view(ChunkState.OCCUPIED));

		Map<Long, ChunkState> demotes = ChunkStateMachine.cardinalDemotesFromNewBorders(
				views::get,
				Set.of(key(0, 0))
		);

		assertEquals(ChunkState.BORDER, demotes.get(key(1, 0)));
		assertEquals(ORG, views.get(key(1, 0)).occupyingOrg());
		assertEquals(1, demotes.size());
		assertFalse(demotes.containsKey(key(-1, 0)));
		assertFalse(demotes.containsKey(key(0, 1)));
		assertFalse(demotes.containsKey(key(1, 1)));
	}

	@Test
	void newBorderDoesNotCascadeFromDemotedHostile() {
		Map<Long, ChunkStateMachine.NeighborChunkView> views = new HashMap<>();
		views.put(key(0, 0), view(ChunkState.BORDER));
		views.put(key(1, 0), hostile(ChunkState.HOSTILE));
		views.put(key(2, 0), view(ChunkState.OCCUPIED));

		Map<Long, ChunkState> demotes = ChunkStateMachine.cardinalDemotesFromNewBorders(
				views::get,
				Set.of(key(0, 0))
		);

		assertEquals(Map.of(key(1, 0), ChunkState.HOSTILE_BORDER), demotes);
		assertFalse(demotes.containsKey(key(2, 0)));
	}

	@Test
	void cardinalDemoteTargetIgnoresUntouchedStates() {
		assertEquals(ChunkState.HOSTILE_BORDER, ChunkStateMachine.cardinalDemoteTarget(ChunkState.BORDER, ChunkState.HOSTILE));
		assertEquals(ChunkState.BORDER, ChunkStateMachine.cardinalDemoteTarget(ChunkState.HOSTILE_BORDER, ChunkState.OCCUPIED));
		assertNull(ChunkStateMachine.cardinalDemoteTarget(ChunkState.BORDER, ChunkState.OCCUPIED));
		assertNull(ChunkStateMachine.cardinalDemoteTarget(ChunkState.BORDER, ChunkState.DEMON));
		assertNull(ChunkStateMachine.cardinalDemoteTarget(ChunkState.BORDER, ChunkState.NATURAL));
		assertNull(ChunkStateMachine.cardinalDemoteTarget(ChunkState.BORDER, ChunkState.SAFE));
		assertNull(ChunkStateMachine.cardinalDemoteTarget(ChunkState.BORDER, ChunkState.DEATH));
		assertNull(ChunkStateMachine.cardinalDemoteTarget(ChunkState.BORDER, ChunkState.HOSTILE_BORDER));
		assertNull(ChunkStateMachine.cardinalDemoteTarget(ChunkState.HOSTILE_BORDER, ChunkState.BORDER));
		assertNull(ChunkStateMachine.cardinalDemoteTarget(ChunkState.HOSTILE_BORDER, ChunkState.HOSTILE));
		assertNull(ChunkStateMachine.cardinalDemoteTarget(ChunkState.OCCUPIED, ChunkState.HOSTILE));
	}

	private static ChunkStateMachine.NeighborChunkView view(ChunkState state) {
		return new ChunkStateMachine.NeighborChunkView(state, ORG);
	}

	private static ChunkStateMachine.NeighborChunkView view(ChunkState state, UUID org) {
		return new ChunkStateMachine.NeighborChunkView(state, org);
	}

	private static ChunkStateMachine.NeighborChunkView hostile(ChunkState state) {
		return new ChunkStateMachine.NeighborChunkView(state, null);
	}

	private static long key(int x, int z) {
		return ChunkPos.asLong(x, z);
	}
}
