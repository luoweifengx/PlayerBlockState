package luowei.player_block_status.lib.org;

import java.util.HashMap;
import java.util.List;
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
 * 服务器级组织成员关系与实体轮询列表持久化（存于主世界 SavedData）。
 */
public class OrganizationData extends SavedData {
	private static final String DATA_ID = "player_block_status_organizations";

	public static final Codec<OrganizationData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(Codec.STRING, OrganizationRecord.CODEC).fieldOf("organizations").forGetter(data -> data.toStringOrgMap()),
			Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("player_orgs").forGetter(data -> data.toStringPlayerMap()),
			UUIDUtil.CODEC.listOf().optionalFieldOf("entity_poll_order", List.of()).forGetter(data -> data.pollList.toPersist())
	).apply(instance, (organizations, playerOrgs, pollOrder) -> {
		OrganizationData data = new OrganizationData();
		organizations.forEach((key, record) -> data.organizations.put(UUID.fromString(key), record));
		playerOrgs.forEach((playerKey, orgKey) -> data.playerToOrg.put(UUID.fromString(playerKey), UUID.fromString(orgKey)));
		data.pollList.load(pollOrder);
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
	private final EntityPollList pollList = new EntityPollList(this::setDirty);

	public static OrganizationData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public Map<UUID, OrganizationRecord> getOrganizations() {
		return organizations;
	}

	public EntityPollList getPollList() {
		return pollList;
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

	/**
	 * @return 若组织因此被解散则返回该组织 id，否则 empty
	 */
	public Optional<UUID> removeMember(UUID playerId) {
		UUID orgId = playerToOrg.remove(playerId);
		if (orgId == null) {
			return Optional.empty();
		}
		OrganizationRecord record = organizations.get(orgId);
		boolean dissolved = false;
		if (record != null) {
			record.removeMember(playerId);
			if (record.members().isEmpty()) {
				organizations.remove(orgId);
				dissolved = true;
			} else if (playerId.equals(record.owner()) && !record.members().isEmpty()) {
				record.setOwner(record.members().iterator().next());
			}
		}
		setDirty();
		return dissolved ? Optional.of(orgId) : Optional.empty();
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

	/**
	 * 启动时对齐轮询列表：补齐缺失的组织；剔除已入组织却仍留在列表中的玩家条目。
	 */
	public void reconcilePollList() {
		pollList.reconcile(organizations.keySet(), playerToOrg.keySet());
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
