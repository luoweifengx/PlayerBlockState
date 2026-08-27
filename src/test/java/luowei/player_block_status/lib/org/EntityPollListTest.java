package luowei.player_block_status.lib.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EntityPollListTest {
	private static final UUID PLAYER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
	private static final UUID PLAYER_B = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
	private static final UUID PLAYER_C = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");
	private static final UUID ORG = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1");
	private static final UUID FROM_ORG = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc1");
	private static final UUID TO_ORG = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc2");

	@Test
	void onOrganizationCreatedRemovesMembersAndAppendsOrg() {
		EntityPollList list = newList(PLAYER_A, PLAYER_B, PLAYER_C);

		list.onOrganizationCreated(ORG, List.of(PLAYER_A, PLAYER_B));

		assertEquals(List.of(PLAYER_C, ORG), list.snapshot());
		assertFalse(list.contains(PLAYER_A));
		assertFalse(list.contains(PLAYER_B));
		assertEquals(1, list.indexOf(ORG));
	}

	@Test
	void onPlayerJoinedOrganizationRemovesOnlyThatPlayer() {
		EntityPollList list = newList(ORG, PLAYER_A, PLAYER_B);
		int orgIndexBefore = list.indexOf(ORG);

		list.onPlayerJoinedOrganization(PLAYER_A);

		assertEquals(List.of(ORG, PLAYER_B), list.snapshot());
		assertEquals(orgIndexBefore, list.indexOf(ORG));
		assertFalse(list.contains(PLAYER_A));
		assertTrue(list.contains(PLAYER_B));
	}

	@Test
	void onPlayerLeftOrganizationKeepsOrgAndAppendsPlayerWhenNotDissolved() {
		EntityPollList list = newList(ORG, PLAYER_B);

		list.onPlayerLeftOrganization(PLAYER_A, ORG, false);

		assertEquals(List.of(ORG, PLAYER_B, PLAYER_A), list.snapshot());
		assertEquals(0, list.indexOf(ORG));
		assertEquals(2, list.indexOf(PLAYER_A));
	}

	@Test
	void onPlayerLeftOrganizationRemovesOrgAndAppendsPlayerWhenDissolved() {
		EntityPollList list = newList(ORG, PLAYER_B);

		list.onPlayerLeftOrganization(PLAYER_A, ORG, true);

		assertEquals(List.of(PLAYER_B, PLAYER_A), list.snapshot());
		assertFalse(list.contains(ORG));
		assertEquals(1, list.indexOf(PLAYER_A));
	}

	@Test
	void onOrganizationsMergedRemovesFromAndKeepsToInPlace() {
		EntityPollList list = newList(FROM_ORG, PLAYER_A, TO_ORG, PLAYER_B);
		int toIndexBefore = list.indexOf(TO_ORG);

		list.onOrganizationsMerged(FROM_ORG, TO_ORG);

		assertEquals(List.of(PLAYER_A, TO_ORG, PLAYER_B), list.snapshot());
		assertFalse(list.contains(FROM_ORG));
		assertEquals(toIndexBefore - 1, list.indexOf(TO_ORG));
		assertEquals(1, list.indexOf(TO_ORG));
	}

	@Test
	void onOrganizationsMergedAppendsToWhenMissing() {
		EntityPollList list = newList(FROM_ORG, PLAYER_A);

		list.onOrganizationsMerged(FROM_ORG, TO_ORG);

		assertEquals(List.of(PLAYER_A, TO_ORG), list.snapshot());
		assertFalse(list.contains(FROM_ORG));
		assertEquals(1, list.indexOf(TO_ORG));
	}

	@Test
	void reconcileAppendsMissingOrgsAndDropsPlayersAlreadyInOrgs() {
		EntityPollList list = newList(PLAYER_A, PLAYER_B, PLAYER_C);

		list.reconcile(List.of(ORG, TO_ORG), List.of(PLAYER_A, PLAYER_C));

		assertEquals(List.of(PLAYER_B, ORG, TO_ORG), list.snapshot());
		assertFalse(list.contains(PLAYER_A));
		assertFalse(list.contains(PLAYER_C));
		assertTrue(list.contains(PLAYER_B));
	}

	@Test
	void indexOfStaysCorrectAfterRemovingMiddleEntry() {
		EntityPollList list = newList(PLAYER_A, PLAYER_B, PLAYER_C, ORG);

		list.onPlayerJoinedOrganization(PLAYER_B);

		assertEquals(List.of(PLAYER_A, PLAYER_C, ORG), list.snapshot());
		assertEquals(0, list.indexOf(PLAYER_A));
		assertEquals(-1, list.indexOf(PLAYER_B));
		assertEquals(1, list.indexOf(PLAYER_C));
		assertEquals(2, list.indexOf(ORG));
		assertFalse(list.contains(PLAYER_B));
	}

	private static EntityPollList newList(UUID... ids) {
		EntityPollList list = new EntityPollList(() -> {
		});
		list.load(List.of(ids));
		return list;
	}
}
