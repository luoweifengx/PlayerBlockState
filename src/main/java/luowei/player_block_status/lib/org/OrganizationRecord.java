package luowei.player_block_status.lib.org;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * 组织元数据：标识、名称、创建者与成员列表。
 */
public final class OrganizationRecord {
	public static final Codec<OrganizationRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(OrganizationRecord::id),
			Codec.STRING.fieldOf("name").forGetter(OrganizationRecord::name),
			UUIDUtil.CODEC.fieldOf("owner").forGetter(OrganizationRecord::owner),
			UUIDUtil.CODEC.listOf().fieldOf("members").forGetter(record -> record.members.stream().toList())
	).apply(instance, (id, name, owner, members) -> new OrganizationRecord(id, name, owner, new HashSet<>(members))));

	private final UUID id;
	private String name;
	private UUID owner;
	private final Set<UUID> members;

	public OrganizationRecord(UUID id, String name, UUID owner, Set<UUID> members) {
		this.id = id;
		this.name = name;
		this.owner = owner;
		this.members = members;
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
	}

	public void removeMember(UUID playerId) {
		members.remove(playerId);
	}
}
