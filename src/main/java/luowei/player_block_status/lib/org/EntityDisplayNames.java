package luowei.player_block_status.lib.org;

import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

/**
 * 将内部实体 UUID（玩家或组织）解析为对外可读名称。
 * <p>
 * 组织使用 {@link OrganizationRecord#name()}；玩家使用持久化名称，
 * 并在进服时按 Minecraft 玩家名更新。旧存档无名称时回退到档案缓存或 UUID。
 */
public final class EntityDisplayNames {
	private EntityDisplayNames() {
	}

	public static void updatePlayerName(MinecraftServer server, UUID playerId, String name) {
		OrganizationData.get(server).updatePlayerName(playerId, name);
	}

	public static String resolve(MinecraftServer server, UUID entityId) {
		if (entityId == null) {
			return "";
		}

		OrganizationData data = OrganizationData.get(server);
		Optional<OrganizationRecord> organization = data.getOrganization(entityId);
		if (organization.isPresent()) {
			String orgName = organization.get().name();
			if (isPresentName(orgName)) {
				return orgName;
			}
			return entityId.toString();
		}

		Optional<String> stored = data.getPlayerName(entityId);
		if (stored.isPresent() && isPresentName(stored.get())) {
			return stored.get();
		}

		GameProfileCache cache = server.getProfileCache();
		if (cache != null) {
			Optional<String> cached = cache.get(entityId).map(GameProfile::getName).filter(EntityDisplayNames::isPresentName);
			if (cached.isPresent()) {
				return cached.get();
			}
		}

		ServerPlayer online = server.getPlayerList().getPlayer(entityId);
		if (online != null) {
			String onlineName = online.getGameProfile().getName();
			if (isPresentName(onlineName)) {
				return onlineName;
			}
		}

		return entityId.toString();
	}

	private static boolean isPresentName(String name) {
		return name != null && !name.isBlank();
	}
}
