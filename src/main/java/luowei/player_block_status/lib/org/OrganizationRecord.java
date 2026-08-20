package luowei.player_block_status.lib.org;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * 组织元数据：标识、组织显示名、创建者、成员列表、待处理邀请，以及地区/领地显示名。
 * {@link #name()} 是组织自身名称；{@link #territoryName()} 是所属地区名称，二者独立。
 */
public final class OrganizationRecord {
	public static final Codec<OrganizationRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(OrganizationRecord::id),
			Codec.STRING.optionalFieldOf("name", "").forGetter(record -> record.name == null ? "" : record.name),
			UUIDUtil.CODEC.fieldOf("owner").forGetter(OrganizationRecord::owner),
			UUIDUtil.CODEC.listOf().fieldOf("members").forGetter(record -> record.members.stream().toList()),
			Codec.STRING.optionalFieldOf("territory_name", "").forGetter(record -> record.territoryName == null ? "" : record.territoryName),
			UUIDUtil.CODEC.listOf().optionalFieldOf("pending_invites", List.of())
					.forGetter(record -> record.pendingInvites.stream().toList())
	).apply(instance, (id, name, owner, members, territoryName, pendingInvites) ->
			new OrganizationRecord(id, name, owner, new HashSet<>(members), territoryName, new HashSet<>(pendingInvites))));

	private final UUID id;
	private String name;
	private UUID owner;
	private final Set<UUID> members;
	private final Set<UUID> pendingInvites;
	private String territoryName;

	public OrganizationRecord(UUID id, String name, UUID owner, Set<UUID> members) {
		this(id, name, owner, members, "", new HashSet<>());
	}

	public OrganizationRecord(UUID id, String name, UUID owner, Set<UUID> members, String territoryName) {
		this(id, name, owner, members, territoryName, new HashSet<>());
	}

	public OrganizationRecord(
			UUID id,
			String name,
			UUID owner,
			Set<UUID> members,
			String territoryName,
			Set<UUID> pendingInvites
	) {
		this.id = id;
		this.name = name;
		this.owner = owner;
		this.members = members;
		this.territoryName = territoryName == null ? "" : territoryName;
		this.pendingInvites = pendingInvites == null ? new HashSet<>() : pendingInvites;
	}

	public UUID id() {
		return id;
	}

	public String name() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	/**
	 * 组织所属地区/领地的自定义显示名；未改过时为空，解析时回退为「{组织名}的领地」。
	 */
	public String territoryName() {
		return territoryName == null ? "" : territoryName;
	}

	public void setTerritoryName(String territoryName) {
		this.territoryName = territoryName == null ? "" : territoryName;
	}

	public UUID owner() {
		return owner;
	}

	public void setOwner(UUID owner) {
		this.owner = owner;
	}

	public Set<UUID> members() {
		return members;
	}

	public boolean isMember(UUID playerId) {
		return members.contains(playerId);
	}

	public void addMember(UUID playerId) {
		members.add(playerId);
		pendingInvites.remove(playerId);
	}

	public void removeMember(UUID playerId) {
		members.remove(playerId);
	}

	public Set<UUID> pendingInvites() {
		return pendingInvites;
	}

	public boolean hasInvite(UUID playerId) {
		return pendingInvites.contains(playerId);
	}

	public void addInvite(UUID playerId) {
		pendingInvites.add(playerId);
	}

	public void removeInvite(UUID playerId) {
		pendingInvites.remove(playerId);
	}
}
