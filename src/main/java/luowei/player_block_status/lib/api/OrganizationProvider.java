package luowei.player_block_status.lib.api;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/**
 * 由消费模组提供：查询玩家所属组织。
 * 玩家加入组织后，所有加减分均计入组织。
 */
@FunctionalInterface
public interface OrganizationProvider {
	Optional<UUID> getOrganizationId(ServerPlayer player);

	OrganizationProvider NONE = player -> Optional.empty();
}
