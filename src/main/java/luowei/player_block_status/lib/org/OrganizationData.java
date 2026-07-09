package luowei.player_block_status.lib.org;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 服务器级组织成员关系持久化（存于主世界 SavedData）。
 */
public class OrganizationData extends SavedData {
	private static final String DATA_ID = "player_block_status_organizations";

	public static final Codec<OrganizationData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(Codec.STRING, OrganizationRecord.CODEC).fieldOf("organizations").forGetter(data -> data.toStringOrgMap()),
			Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("player_orgs").forGetter(data -> data.toStringPlayerMap())
	).apply(instance, (organizations, playerOrgs) -> {
		OrganizationData data = new OrganizationData();
		organizations.forEach((key, record) -> data.organizations.put(UUID.fromString(key), record));
		playerOrgs.forEach((playerKey, orgKey) -> data.playerToOrg.put(UUID.fromString(playerKey), UUID.fromString(orgKey)));
		return data;
	}));

	public static final SavedDataType<OrganizationData> TYPE = new SavedDataType<>(
			DATA_ID,
			context -> new OrganizationData(),
			context -> OrganizationData.CODEC,
			null
	);

	private final Map<UUID, OrganizationRecord> organizations = new HashMap<>();
	private final Map<UUID, UUID> playerToOrg = new HashMap<>();

	public static OrganizationData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public Map<UUID, OrganizationRecord> getOrganizations() {
		return organizations;
	}

	public Optional<OrganizationRecord> getOrganization(UUID orgId) {
		return Optional.ofNullable(organizations.get(orgId));
	}

	public Optional<UUID> getPlayerOrganization(UUID playerId) {
		return Optional.ofNullable(playerToOrg.get(playerId));
	}

	public OrganizationRecord createOrganization(UUID orgId, String name, UUID ownerId) {
		OrganizationRecord record = new OrganizationRecord(orgId, name, ownerId, new java.util.HashSet<>());
		record.addMember(ownerId);
		organizations.put(orgId, record);
		playerToOrg.put(ownerId, orgId);
		setDirty();
		return record;
	}

	public void addMember(UUID orgId, UUID playerId) {
		OrganizationRecord record = organizations.get(orgId);
		if (record == null) {
			throw new IllegalArgumentException("Organization not found: " + orgId);
		}
		record.addMember(playerId);
		playerToOrg.put(playerId, orgId);
		setDirty();
	}

	public void removeMember(UUID playerId) {
		UUID orgId = playerToOrg.remove(playerId);
		if (orgId == null) {
			return;
		}
		OrganizationRecord record = organizations.get(orgId);
		if (record != null) {
			record.removeMember(playerId);
			if (record.members().isEmpty()) {
				organizations.remove(orgId);
			} else if (playerId.equals(record.owner()) && !record.members().isEmpty()) {
				record.setOwner(record.members().iterator().next());
			}
		}
		setDirty();
	}

	public void mergeOrganizations(UUID fromOrgId, UUID toOrgId) {
		OrganizationRecord from = organizations.remove(fromOrgId);
		OrganizationRecord to = organizations.get(toOrgId);
		if (from == null || to == null) {
			throw new IllegalArgumentException("Organization merge source or target missing");
		}

		for (UUID member : from.members()) {
			to.addMember(member);
			playerToOrg.put(member, toOrgId);
		}
		setDirty();
	}

	private Map<String, OrganizationRecord> toStringOrgMap() {
		Map<String, OrganizationRecord> result = new HashMap<>();
		organizations.forEach((id, record) -> result.put(id.toString(), record));
		return result;
	}

	private Map<String, String> toStringPlayerMap() {
		Map<String, String> result = new HashMap<>();
		playerToOrg.forEach((playerId, orgId) -> result.put(playerId.toString(), orgId.toString()));
		return result;
	}
}
