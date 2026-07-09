package luowei.player_block_status.lib.org;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.api.OrganizationProvider;

/**
 * 内置组织成员查询，供领土计分在写入时解析玩家 → 组织 UUID。
 */
public enum InternalOrganizationProvider implements OrganizationProvider {
	INSTANCE;

	@Override
	public Optional<UUID> getOrganizationId(ServerPlayer player) {
		return OrganizationData.get(player.getServer()).getPlayerOrganization(player.getUUID());
	}
}
