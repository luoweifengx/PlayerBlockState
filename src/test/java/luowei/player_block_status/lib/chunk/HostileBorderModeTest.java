package luowei.player_block_status.lib.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class HostileBorderModeTest {
	@Test
	void parsesInfectionAndSpreadIds() {
		assertEquals(HostileBorderMode.INFECTION, HostileBorderMode.fromId("infection", HostileBorderMode.SPREAD));
		assertEquals(HostileBorderMode.SPREAD, HostileBorderMode.fromId("spread", HostileBorderMode.INFECTION));
		assertEquals(HostileBorderMode.INFECTION, HostileBorderMode.fromId("INFECTION", HostileBorderMode.SPREAD));
		assertEquals(HostileBorderMode.SPREAD, HostileBorderMode.fromId("Spread", HostileBorderMode.INFECTION));
	}

	@Test
	void unknownIdUsesFallback() {
		assertEquals(HostileBorderMode.INFECTION, HostileBorderMode.fromId("directSpread", HostileBorderMode.INFECTION));
		assertNull(HostileBorderMode.fromId("nope", null));
	}
}
