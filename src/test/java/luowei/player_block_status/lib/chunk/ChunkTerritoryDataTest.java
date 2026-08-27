package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

class ChunkTerritoryDataTest {
	private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID ORG = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID OTHER = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	@AfterEach
	void restoreTerritoryConfigDefaults() {
		TerritoryConfig.deathRecoveryPerDay = 30;
		TerritoryConfig.deathThreshold = -100;
	}

	@Test
	void remapEntityMergesMapsAndRewritesOccupyingOrg() {
		ChunkTerritoryData data = ChunkTerritoryData.createEmpty();
		BlockPos fromOnly = new BlockPos(20, 70, -5);
		BlockPos fromSecond = new BlockPos(21, 71, -5);
		BlockPos otherBlock = new BlockPos(22, 72, -5);
		data.addPlacedBlock(fromOnly, PLAYER);
		data.addPlacedBlock(fromSecond, PLAYER);
		data.addPlacedBlock(otherBlock, OTHER);

		data.getScoreModifiers().put(PLAYER, 10);
		data.getScoreModifiers().put(ORG, 5);
		data.getStayScores().put(PLAYER, 7);
		data.getStayScores().put(ORG, 3);
		data.getCachedScores().put(PLAYER, 40);
		data.getCachedScores().put(ORG, 20);
		data.setOccupyingOrg(PLAYER);

		data.remapEntity(PLAYER, ORG);

		assertEquals(ORG, data.getPlacedBlockOwner(fromOnly));
		assertEquals(ORG, data.getPlacedBlockOwner(fromSecond));
		assertEquals(OTHER, data.getPlacedBlockOwner(otherBlock));
		assertFalse(data.getPlacedBlocks().containsValue(PLAYER));

		assertFalse(data.getScoreModifiers().containsKey(PLAYER));
		assertEquals(15, data.getScoreModifiers().get(ORG));
		assertFalse(data.getStayScores().containsKey(PLAYER));
		assertEquals(10, data.getStayScores().get(ORG));
		assertFalse(data.getCachedScores().containsKey(PLAYER));
		assertEquals(60, data.getCachedScores().get(ORG));
		assertEquals(ORG, data.getOccupyingOrg());
	}

	@Test
	void remapEntityLeavesOccupyingOrgUnchangedWhenNotFrom() {
		ChunkTerritoryData data = ChunkTerritoryData.createEmpty();
		data.setOccupyingOrg(OTHER);
		data.getScoreModifiers().put(PLAYER, 4);

		data.remapEntity(PLAYER, ORG);

		assertEquals(OTHER, data.getOccupyingOrg());
		assertEquals(4, data.getScoreModifiers().get(ORG));
		assertFalse(data.getScoreModifiers().containsKey(PLAYER));
	}

	@Test
	void clearStayScoresEmptiesOnlyStayMap() {
		ChunkTerritoryData data = ChunkTerritoryData.createEmpty();
		data.getStayScores().put(PLAYER, 9);
		data.getStayScores().put(ORG, 2);
		data.getScoreModifiers().put(PLAYER, -5);
		data.getCachedScores().put(PLAYER, 12);

		data.clearStayScores();

		assertTrue(data.getStayScores().isEmpty());
		assertEquals(-5, data.getScoreModifiers().get(PLAYER));
		assertEquals(12, data.getCachedScores().get(PLAYER));
	}

	@Test
	void applyDeathRecoveryAddsPerDayWhenStateIsDeath() {
		Map<UUID, Integer> modifiers = new HashMap<>();
		modifiers.put(PLAYER, -80);
		modifiers.put(ORG, -20);

		ChunkStateMachine.applyDeathRecoveryToModifiers(ChunkState.DEATH, modifiers);

		assertEquals(-80 + TerritoryConfig.deathRecoveryPerDay, modifiers.get(PLAYER));
		assertEquals(-20 + TerritoryConfig.deathRecoveryPerDay, modifiers.get(ORG));
	}

	@Test
	void applyDeathRecoveryDoesNotChangeNonDeathStates() {
		Map<UUID, Integer> modifiers = new HashMap<>();
		modifiers.put(PLAYER, -80);

		ChunkStateMachine.applyDeathRecoveryToModifiers(ChunkState.NATURAL, modifiers);
		assertEquals(-80, modifiers.get(PLAYER));

		ChunkStateMachine.applyDeathRecoveryToModifiers(ChunkState.OCCUPIED, modifiers);
		assertEquals(-80, modifiers.get(PLAYER));

		ChunkStateMachine.applyDeathRecoveryToModifiers(ChunkState.SAFE, modifiers);
		assertEquals(-80, modifiers.get(PLAYER));
	}

	@Test
	void packLocalPosAndUnpackGlobalPosRoundTrip() {
		BlockPos original = new BlockPos(20, 100, -37);
		int chunkX = original.getX() >> 4;
		int chunkZ = original.getZ() >> 4;
		long chunkKey = (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);

		long packed = ChunkTerritoryData.packLocalPos(original);
		BlockPos unpacked = ChunkTerritoryData.unpackGlobalPos(chunkKey, packed);

		assertEquals(original, unpacked);
	}

	@Test
	void packLocalPosAndUnpackGlobalPosRoundTripNegativeY() {
		BlockPos original = new BlockPos(-3, -20, 48);
		int chunkX = original.getX() >> 4;
		int chunkZ = original.getZ() >> 4;
		long chunkKey = (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);

		assertEquals(original, ChunkTerritoryData.unpackGlobalPos(chunkKey, ChunkTerritoryData.packLocalPos(original)));
	}

	@Test
	void hasTerritoryDataFalseWhenEmpty() {
		assertFalse(ChunkTerritoryData.createEmpty().hasTerritoryData());
	}

	@Test
	void addPlacedBlockSamePosOverwritesOwnerWithoutDuplicating() {
		ChunkTerritoryData data = ChunkTerritoryData.createEmpty();
		BlockPos pos = new BlockPos(8, 64, 8);
		data.addPlacedBlock(pos, PLAYER);
		data.addPlacedBlock(pos, ORG);

		assertEquals(1, data.getPlacedBlocks().size());
		assertEquals(ORG, data.getPlacedBlockOwner(pos));
	}

	@Test
	void hasTerritoryDataTrueWhenPlacedBlocksPresent() {
		ChunkTerritoryData data = ChunkTerritoryData.createEmpty();
		data.addPlacedBlock(new BlockPos(1, 64, 1), PLAYER);
		assertTrue(data.hasTerritoryData());
	}

	@Test
	void hasTerritoryDataTrueWhenAnyScoreMapPresent() {
		ChunkTerritoryData modifiersOnly = ChunkTerritoryData.createEmpty();
		modifiersOnly.getScoreModifiers().put(PLAYER, 1);
		assertTrue(modifiersOnly.hasTerritoryData());

		ChunkTerritoryData stayOnly = ChunkTerritoryData.createEmpty();
		stayOnly.getStayScores().put(PLAYER, 1);
		assertTrue(stayOnly.hasTerritoryData());

		ChunkTerritoryData cachedOnly = ChunkTerritoryData.createEmpty();
		cachedOnly.getCachedScores().put(PLAYER, 1);
		assertTrue(cachedOnly.hasTerritoryData());
	}

	@Test
	void hasTerritoryDataTrueWhenNonNaturalOrOccupyingOrg() {
		ChunkTerritoryData nonNatural = ChunkTerritoryData.createEmpty();
		nonNatural.setState(ChunkState.OCCUPIED);
		assertTrue(nonNatural.hasTerritoryData());

		ChunkTerritoryData withOrg = ChunkTerritoryData.createEmpty();
		withOrg.setOccupyingOrg(ORG);
		assertTrue(withOrg.hasTerritoryData());
		assertNull(ChunkTerritoryData.createEmpty().getOccupyingOrg());
	}
}
