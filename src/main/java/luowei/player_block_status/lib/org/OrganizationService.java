package luowei.player_block_status.lib.org;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.api.PlayerBlockStatusLib;

/**
 * 组织业务：创建、加入、离开、合并，并同步领土数据迁移。
 */
public final class OrganizationService {
	private OrganizationService() {
	}

	public static OrganizationRecord createOrganization(MinecraftServer server, ServerPlayer founder, String name) {
		if (OrganizationData.get(server).getPlayerOrganization(founder.getUUID()).isPresent()) {
			throw new OrganizationException("Already in an organization. Leave first with /pbs org leave");
		}

		UUID orgId = UUID.randomUUID();
		OrganizationRecord record = OrganizationData.get(server).createOrganization(orgId, name, founder.getUUID());
		transferPlayerTerritoryAcrossDimensions(server, founder.getUUID(), orgId);
		return record;
	}

	public static void joinOrganization(MinecraftServer server, ServerPlayer player, UUID orgId) {
		OrganizationData data = OrganizationData.get(server);
		if (data.getOrganization(orgId).isEmpty()) {
			throw new OrganizationException("Organization not found: " + orgId);
		}
		if (data.getPlayerOrganization(player.getUUID()).isPresent()) {
			throw new OrganizationException("Already in an organization. Leave first with /pbs org leave");
		}

		data.addMember(orgId, player.getUUID());
		transferPlayerTerritoryAcrossDimensions(server, player.getUUID(), orgId);
	}

	public static void leaveOrganization(MinecraftServer server, ServerPlayer player) {
		OrganizationData data = OrganizationData.get(server);
		if (data.getPlayerOrganization(player.getUUID()).isEmpty()) {
			throw new OrganizationException("You are not in an organization");
		}
		data.removeMember(player.getUUID());
	}

	public static void mergeOrganizations(MinecraftServer server, UUID fromOrgId, UUID toOrgId) {
		if (fromOrgId.equals(toOrgId)) {
			throw new OrganizationException("Cannot merge an organization into itself");
		}
		OrganizationData data = OrganizationData.get(server);
		if (data.getOrganization(fromOrgId).isEmpty() || data.getOrganization(toOrgId).isEmpty()) {
			throw new OrganizationException("Source or target organization not found");
		}

		data.mergeOrganizations(fromOrgId, toOrgId);
		for (ServerLevel level : server.getAllLevels()) {
			PlayerBlockStatusLib.remapOrganization(level, fromOrgId, toOrgId);
		}
	}

	public static Optional<UUID> getOrganizationId(MinecraftServer server, UUID playerId) {
		return OrganizationData.get(server).getPlayerOrganization(playerId);
	}

	public static Optional<OrganizationRecord> getOrganization(MinecraftServer server, UUID orgId) {
		return OrganizationData.get(server).getOrganization(orgId);
	}

	public static Component describePlayerOrg(MinecraftServer server, ServerPlayer player) {
		return OrganizationData.get(server).getPlayerOrganization(player.getUUID())
				.flatMap(orgId -> OrganizationData.get(server).getOrganization(orgId))
				.map(record -> Component.literal("Organization{name=" + record.name()
						+ ", id=" + record.id() + ", owner=" + record.owner()
						+ ", members=" + record.members().size() + "}"))
				.orElse(Component.literal("Not in an organization"));
	}

	private static void transferPlayerTerritoryAcrossDimensions(MinecraftServer server, UUID playerId, UUID orgId) {
		for (ServerLevel level : server.getAllLevels()) {
			PlayerBlockStatusLib.transferPlayerToOrg(level, playerId, orgId);
		}
	}

	public static final class OrganizationException extends RuntimeException {
		public OrganizationException(String message) {
			super(message);
		}
	}
}
