package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EntityChunkIndexTest {
	private static final UUID PLAYER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID ORG = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
	private static final UUID FROM_ORG = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
	private static final UUID TO_ORG = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

	private static final long KEY_OCCUPIED_PLAYER = 11L;
	private static final long KEY_OCCUPIED_ORG = 12L;
	private static final long KEY_BORDER_PLAYER = 21L;
	private static final long KEY_BORDER_ORG = 22L;

	@Test
	void transferPlayerToOrgMergesOccupiedAndBorderThenDropsPlayer() {
		EntityChunkIndex index = seededPlayerAndOrg();

		index.transferPlayerToOrg(PLAYER, ORG);

		assertTrue(index.getOccupiedChunks(PLAYER).isEmpty());
		assertTrue(index.getBorderChunks(PLAYER).isEmpty());
		assertEquals(Set.of(KEY_OCCUPIED_PLAYER, KEY_OCCUPIED_ORG), index.getOccupiedChunks(ORG));
		assertEquals(Set.of(KEY_BORDER_PLAYER, KEY_BORDER_ORG), index.getBorderChunks(ORG));
		assertEquals(0, index.getOccupiedCount(PLAYER));
		assertEquals(0, index.getBorderCount(PLAYER));
		assertEquals(0, index.getOwnedCount(PLAYER));
		assertEquals(2, index.getOccupiedCount(ORG));
		assertEquals(2, index.getBorderCount(ORG));
		assertEquals(4, index.getOwnedCount(ORG));
	}

	@Test
	void mergeOrganizationMovesFromIntoToThenDropsFrom() {
		EntityChunkIndex index = new EntityChunkIndex();
		index.replaceChunk(1L, ChunkState.OCCUPIED, FROM_ORG);
		index.replaceChunk(2L, ChunkState.BORDER, FROM_ORG);
		index.replaceChunk(3L, ChunkState.OCCUPIED, TO_ORG);
		index.replaceChunk(4L, ChunkState.BORDER, TO_ORG);

		index.mergeOrganization(FROM_ORG, TO_ORG);

		assertTrue(index.getOccupiedChunks(FROM_ORG).isEmpty());
		assertTrue(index.getBorderChunks(FROM_ORG).isEmpty());
		assertTrue(index.getOwnedChunks(FROM_ORG).isEmpty());
		assertEquals(Set.of(1L, 3L), index.getOccupiedChunks(TO_ORG));
		assertEquals(Set.of(2L, 4L), index.getBorderChunks(TO_ORG));
		assertEquals(0, index.getOwnedCount(FROM_ORG));
		assertEquals(2, index.getOccupiedCount(TO_ORG));
		assertEquals(2, index.getBorderCount(TO_ORG));
		assertEquals(4, index.getOwnedCount(TO_ORG));
	}

	@Test
	void replaceChunkOccupiedGoesToOccupiedTable() {
		EntityChunkIndex index = new EntityChunkIndex();

		index.replaceChunk(7L, ChunkState.OCCUPIED, ORG);

		assertEquals(Set.of(7L), index.getOccupiedChunks(ORG));
		assertTrue(index.getBorderChunks(ORG).isEmpty());
		assertEquals(1, index.getOccupiedCount(ORG));
		assertEquals(0, index.getBorderCount(ORG));
		assertEquals(1, index.getOwnedCount(ORG));
	}

	@Test
	void replaceChunkBorderGoesToBorderTable() {
		EntityChunkIndex index = new EntityChunkIndex();

		index.replaceChunk(8L, ChunkState.BORDER, ORG);

		assertTrue(index.getOccupiedChunks(ORG).isEmpty());
		assertEquals(Set.of(8L), index.getBorderChunks(ORG));
		assertEquals(0, index.getOccupiedCount(ORG));
		assertEquals(1, index.getBorderCount(ORG));
		assertEquals(1, index.getOwnedCount(ORG));
	}

	@Test
	void replaceChunkDemonDoesNotEnterOccupiedOrBorderTables() {
		EntityChunkIndex index = new EntityChunkIndex();
		index.replaceChunk(9L, ChunkState.OCCUPIED, ORG);

		index.replaceChunk(9L, ChunkState.DEMON, ORG);

		assertTrue(index.getOccupiedChunks(ORG).isEmpty());
		assertTrue(index.getBorderChunks(ORG).isEmpty());
		assertEquals(0, index.getOwnedCount(ORG));
	}

	@Test
	void replaceChunkNullOccupyingOrgRemovesFromBothTables() {
		EntityChunkIndex index = new EntityChunkIndex();
		index.replaceChunk(10L, ChunkState.BORDER, ORG);

		index.replaceChunk(10L, ChunkState.BORDER, null);

		assertTrue(index.getOccupiedChunks(ORG).isEmpty());
		assertTrue(index.getBorderChunks(ORG).isEmpty());
		assertEquals(0, index.getOwnedCount(ORG));
	}

	@Test
	void getOwnedChunksIsOccupiedUnionBorder() {
		EntityChunkIndex index = seededPlayerAndOrg();

		assertEquals(Set.of(KEY_OCCUPIED_ORG, KEY_BORDER_ORG), index.getOwnedChunks(ORG));
		assertEquals(Set.of(KEY_OCCUPIED_PLAYER, KEY_BORDER_PLAYER), index.getOwnedChunks(PLAYER));
		assertEquals(1, index.getOccupiedCount(ORG));
		assertEquals(1, index.getBorderCount(ORG));
		assertEquals(2, index.getOwnedCount(ORG));
		assertEquals(1, index.getOccupiedCount(PLAYER));
		assertEquals(1, index.getBorderCount(PLAYER));
		assertEquals(2, index.getOwnedCount(PLAYER));
	}

	@Test
	void countsUpdateOnlyWhenMapGainsOrLosesKeys() {
		EntityChunkIndex index = new EntityChunkIndex();
		index.replaceChunk(1L, ChunkState.OCCUPIED, ORG);
		index.replaceChunk(2L, ChunkState.OCCUPIED, ORG);
		assertEquals(2, index.getOccupiedCount(ORG));

		index.replaceChunk(1L, ChunkState.OCCUPIED, ORG);
		assertEquals(2, index.getOccupiedCount(ORG));
		assertEquals(2, index.getOwnedCount(ORG));

		index.replaceChunk(1L, ChunkState.BORDER, ORG);
		assertEquals(1, index.getOccupiedCount(ORG));
		assertEquals(1, index.getBorderCount(ORG));
		assertEquals(2, index.getOwnedCount(ORG));

		index.replaceChunk(2L, ChunkState.NATURAL, null);
		assertEquals(0, index.getOccupiedCount(ORG));
		assertEquals(1, index.getBorderCount(ORG));
		assertEquals(1, index.getOwnedCount(ORG));
	}

	@Test
	void loadRebuildsCountsFromPersistedMaps() {
		EntityChunkIndex index = new EntityChunkIndex();
		index.load(
				Map.of(ORG, Set.of(1L, 2L), PLAYER, Set.of(3L)),
				Map.of(ORG, Set.of(4L))
		);

		assertEquals(2, index.getOccupiedCount(ORG));
		assertEquals(1, index.getBorderCount(ORG));
		assertEquals(3, index.getOwnedCount(ORG));
		assertEquals(1, index.getOccupiedCount(PLAYER));
		assertEquals(0, index.getBorderCount(PLAYER));
		assertEquals(1, index.getOwnedCount(PLAYER));
	}

	@Test
	void rebuildFromMatchesReplaceChunk() {
		Map<Long, ChunkTerritoryData> chunks = new HashMap<>();
		chunks.put(1L, chunk(ChunkState.OCCUPIED, ORG));
		chunks.put(2L, chunk(ChunkState.BORDER, ORG));
		chunks.put(3L, chunk(ChunkState.NATURAL, ORG));
		chunks.put(4L, chunk(ChunkState.OCCUPIED, null));
		chunks.put(5L, chunk(ChunkState.HOSTILE_BORDER, PLAYER));

		EntityChunkIndex rebuilt = new EntityChunkIndex();
		rebuilt.rebuildFrom(chunks);

		EntityChunkIndex replaced = new EntityChunkIndex();
		replaced.replaceChunk(1L, ChunkState.OCCUPIED, ORG);
		replaced.replaceChunk(2L, ChunkState.BORDER, ORG);
		replaced.replaceChunk(3L, ChunkState.NATURAL, ORG);
		replaced.replaceChunk(4L, ChunkState.OCCUPIED, null);
		replaced.replaceChunk(5L, ChunkState.HOSTILE_BORDER, PLAYER);

		assertEquals(replaced.getOccupiedChunks(ORG), rebuilt.getOccupiedChunks(ORG));
		assertEquals(replaced.getBorderChunks(ORG), rebuilt.getBorderChunks(ORG));
		assertEquals(replaced.getOwnedChunks(PLAYER), rebuilt.getOwnedChunks(PLAYER));
		assertEquals(Set.of(1L), rebuilt.getOccupiedChunks(ORG));
		assertEquals(Set.of(2L), rebuilt.getBorderChunks(ORG));
		assertTrue(rebuilt.getOwnedChunks(PLAYER).isEmpty());
		assertEquals(1, rebuilt.getOccupiedCount(ORG));
		assertEquals(1, rebuilt.getBorderCount(ORG));
		assertEquals(2, rebuilt.getOwnedCount(ORG));
		assertEquals(0, rebuilt.getOwnedCount(PLAYER));
	}

	private static EntityChunkIndex seededPlayerAndOrg() {
		EntityChunkIndex index = new EntityChunkIndex();
		index.replaceChunk(KEY_OCCUPIED_PLAYER, ChunkState.OCCUPIED, PLAYER);
		index.replaceChunk(KEY_BORDER_PLAYER, ChunkState.BORDER, PLAYER);
		index.replaceChunk(KEY_OCCUPIED_ORG, ChunkState.OCCUPIED, ORG);
		index.replaceChunk(KEY_BORDER_ORG, ChunkState.BORDER, ORG);
		return index;
	}

	private static ChunkTerritoryData chunk(ChunkState state, UUID occupyingOrg) {
		ChunkTerritoryData data = ChunkTerritoryData.createEmpty();
		data.setState(state);
		data.setOccupyingOrg(occupyingOrg);
		return data;
	}
}
