package luowei.player_block_status.lib.api;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 计分账户钩子：放置、停留、死亡写入时，把玩家映射到要入账的 UUID。
 * <p>
 * 按玩家 UUID 解析（含离线）。返回 empty 则记到玩家自己的 UUID。这不是完整的组织系统：
 * 不负责创建/加入/踢人、显示名、进界文案，也不会在成员变动时迁移已有领地。那些要由本库内置组织，
 * 或由外部模组自己做完后再调用 {@code transferPlayerToOrg} / {@code remapOrganization}。
 * <p>
 * 默认实现是「外部空档 + 内置组织」。不要在开服时整段换成只有内置；外部层请走
 * {@link luowei.player_block_status.lib.compat.ExternalOrganizationBridge}。
 */
@FunctionalInterface
public interface OrganizationProvider {
	Optional<UUID> getOrganizationId(MinecraftServer server, UUID playerId);

	default Optional<UUID> getOrganizationId(ServerPlayer player) {
		if (player == null) {
			return Optional.empty();
		}
		return getOrganizationId(player.getServer(), player.getUUID());
	}

	OrganizationProvider NONE = (server, playerId) -> Optional.empty();
}
