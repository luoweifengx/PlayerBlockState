package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.api.OrganizationProvider;

class RegionManagerPlaceNotifyTest {
	private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID ORG = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void onBlockPlacedNullArgsAreNoOp() {
		RegionManager.onBlockPlaced(null, new BlockPos(0, 64, 0), PLAYER, OrganizationProvider.NONE);
		RegionManager.onBlockPlaced(null, null, (UUID) null, null);
		RegionManager.onBlockPlaced(null, new BlockPos(0, 64, 0), (ServerPlayer) null, OrganizationProvider.NONE);
	}

	@Test
	void organizationProviderFallsBackToGivenUuid() {
		assertEquals(Optional.empty(), OrganizationProvider.NONE.getOrganizationId(null, PLAYER));
		assertEquals(PLAYER, OrganizationProvider.NONE.getOrganizationId(null, PLAYER).orElse(PLAYER));
		assertEquals(ORG, OrganizationProvider.NONE.getOrganizationId(null, ORG).orElse(ORG));
	}

	@Test
	void organizationProviderMapsPlayerToOrg() {
		OrganizationProvider mapped = (server, playerId) -> Optional.of(ORG);
		assertEquals(Optional.of(ORG), mapped.getOrganizationId(null, PLAYER));
		assertEquals(ORG, mapped.getOrganizationId(null, PLAYER).orElse(PLAYER));
	}
}
