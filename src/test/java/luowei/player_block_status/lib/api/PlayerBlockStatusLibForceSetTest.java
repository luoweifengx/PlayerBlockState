package luowei.player_block_status.lib.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.chunk.ChunkState;

class PlayerBlockStatusLibForceSetTest {
	private static ChunkPos ORIGIN;
	private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		ORIGIN = new ChunkPos(0, 0);
	}

	@Test
	void nullLevelIsNoOp() {
		assertEquals(0, PlayerBlockStatusLib.forceSetChunks(null, ORIGIN, 0, ChunkState.OCCUPIED, true, OWNER));
		assertEquals(0, PlayerBlockStatusLib.forceSetChunkState(null, ORIGIN, ChunkState.OCCUPIED));
		assertEquals(0, PlayerBlockStatusLib.forceSetChunkOwner(null, ORIGIN, OWNER));
	}

	@Test
	void nullCenterOrStateIsNoOp() {
		assertEquals(0, PlayerBlockStatusLib.forceSetChunks(null, null, 0, ChunkState.OCCUPIED, false, null));
		assertEquals(0, PlayerBlockStatusLib.forceSetChunkState(null, ORIGIN, null));
		assertEquals(0, PlayerBlockStatusLib.forceSetChunkOwner(null, null, OWNER));
	}
}
