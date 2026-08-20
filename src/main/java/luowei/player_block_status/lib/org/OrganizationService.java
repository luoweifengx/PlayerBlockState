package luowei.player_block_status.lib.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.api.PlayerBlockStatusLib;

/**
 * 组织业务：创建、加入、离开、合并，并同步领土数据迁移与实体轮询列表。
 */
public final class OrganizationService {
	private OrganizationService() {
	}

	public static OrganizationRecord createOrganization(MinecraftServer server, ServerPlayer founder, String name) {
		OrganizationData data = OrganizationData.get(server);
		if (data.getPlayerOrganization(founder.getUUID()).isPresent()) {
			throw new OrganizationException("Already in an organization. Leave first with /pbs org leave");
		}

		String orgName = name == null ? "" : name.trim();
		for (OrganizationRecord existing : data.getOrganizations().values()) {
			if (orgName.equals(existing.name())) {
				throw new OrganizationException("Organization already exists: " + orgName);
			}
		}

		UUID orgId = UUID.randomUUID();
		OrganizationRecord record = data.createOrganization(orgId, orgName, founder.getUUID());
		data.getPollList().onOrganizationCreated(orgId, List.of(founder.getUUID()));
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
		data.getPollList().onPlayerJoinedOrganization(player.getUUID());
		transferPlayerTerritoryAcrossDimensions(server, player.getUUID(), orgId);
	}

	public static void leaveOrganization(MinecraftServer server, ServerPlayer player) {
		OrganizationData data = OrganizationData.get(server);
		Optional<UUID> orgId = data.getPlayerOrganization(player.getUUID());
		if (orgId.isEmpty()) {
			throw new OrganizationException("You are not in an organization");
		}
		Optional<UUID> dissolved = data.removeMember(player.getUUID());
		data.getPollList().onPlayerLeftOrganization(
				player.getUUID(),
				orgId.get(),
				dissolved.isPresent()
		);
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
		data.getPollList().onOrganizationsMerged(fromOrgId, toOrgId);
		for (ServerLevel level : server.getAllLevels()) {
			PlayerBlockStatusLib.remapOrganization(level, fromOrgId, toOrgId);
		}
	}

	/**
	 * 确保未入组织的玩家出现在轮询列表队尾（若尚不存在）。
	 */
	public static void ensureSoloPlayerInPollList(MinecraftServer server, UUID playerId) {
		OrganizationData data = OrganizationData.get(server);
		if (data.getPlayerOrganization(playerId).isPresent()) {
			return;
		}
		data.getPollList().ensurePresent(playerId);
	}

	public static Optional<UUID> getOrganizationId(MinecraftServer server, UUID playerId) {
		return OrganizationData.get(server).getPlayerOrganization(playerId);
	}

	public static Optional<OrganizationRecord> getOrganization(MinecraftServer server, UUID orgId) {
		return OrganizationData.get(server).getOrganization(orgId);
	}

	public static EntityPollList getPollList(MinecraftServer server) {
		return OrganizationData.get(server).getPollList();
	}

	public static Component describePlayerOrg(MinecraftServer server, ServerPlayer player) {
		return OrganizationData.get(server).getPlayerOrganization(player.getUUID())
				.flatMap(orgId -> OrganizationData.get(server).getOrganization(orgId))
				.map(record -> Component.literal("Organization{name=" + record.name()
						+ ", territory=" + EntityDisplayNames.resolveTerritoryName(server, record.id())
						+ ", id=" + record.id() + ", owner=" + record.owner()
						+ ", members=" + record.members().size() + "}"))
				.orElse(Component.literal("Not in an organization"));
	}

	/**
	 * 改玩家个人地区/领地显示名（不是玩家名）。加入组织后此字段仍保留，
	 * 但进领地提示看的是区块 occupyingOrg（可能是组织 UUID）。
	 */
	public static String setPlayerTerritoryName(MinecraftServer server, UUID playerId, String rawName) {
		String name = requireTerritoryName(rawName);
		OrganizationData.get(server).setPlayerTerritoryName(playerId, name);
		return name;
	}

	/**
	 * 改组织地区/领地显示名（不是组织名）。
	 */
	public static String setOrganizationTerritoryName(MinecraftServer server, UUID orgId, String rawName) {
		if (OrganizationData.get(server).getOrganization(orgId).isEmpty()) {
			throw new OrganizationException("Organization not found: " + orgId);
		}
		String name = requireTerritoryName(rawName);
		OrganizationData.get(server).setOrganizationTerritoryName(orgId, name);
		return name;
	}

	private static String requireTerritoryName(String rawName) {
		try {
			return EntityDisplayNames.requireValidTerritoryName(rawName);
		} catch (IllegalArgumentException exception) {
			throw new OrganizationException(exception.getMessage());
		}
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
