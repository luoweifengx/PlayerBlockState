package luowei.player_block_status.lib.org;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.api.OrganizationProvider;

/**
 * 内置组织成员查询，供领土计分在写入时解析玩家 → 组织 UUID。
 */
public enum InternalOrganizationProvider implements OrganizationProvider {
	INSTANCE;

	@Override
	public Optional<UUID> getOrganizationId(MinecraftServer server, UUID playerId) {
		if (server == null || playerId == null) {
			return Optional.empty();
		}
		return OrganizationData.get(server).getPlayerOrganization(playerId);
	}

	@Override
	public Optional<UUID> getOrganizationId(ServerPlayer player) {
		if (player == null) {
			return Optional.empty();
		}
		return getOrganizationId(player.getServer(), player.getUUID());
	}
}
