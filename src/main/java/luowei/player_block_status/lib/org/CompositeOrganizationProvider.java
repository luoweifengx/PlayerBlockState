package luowei.player_block_status.lib.org;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.api.OrganizationProvider;
import luowei.player_block_status.lib.compat.ExternalOrganizationBridge;

/**
 * 固定组合：外部层（本轮 empty）→ 内置组织。都 empty 时由调用方回退到玩家 UUID。
 * <p>
 * 开服不要再把它换成「只有内置」；外部能力走 {@link ExternalOrganizationBridge#set}。
 */
public enum CompositeOrganizationProvider implements OrganizationProvider {
	INSTANCE;

	@Override
	public Optional<UUID> getOrganizationId(MinecraftServer server, UUID playerId) {
		Optional<UUID> external = ExternalOrganizationBridge.current().getOrganizationId(server, playerId);
		if (external.isPresent()) {
			return external;
		}
		return InternalOrganizationProvider.INSTANCE.getOrganizationId(server, playerId);
	}

	@Override
	public Optional<UUID> getOrganizationId(ServerPlayer player) {
		if (player == null) {
			return Optional.empty();
		}
		return getOrganizationId(player.getServer(), player.getUUID());
	}
}
