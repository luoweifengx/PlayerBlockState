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
 * <p>
 * 地区/领地显示名与上述实体名独立：未自定义时为「{实体显示名}的领地」；
 * 一旦改过则原样使用自定义字符串，不再自动拼接「的领地」。
 */
public final class EntityDisplayNames {
	public static final int TERRITORY_NAME_MAX_LENGTH = 32;
	public static final String DEFAULT_TERRITORY_NAME_SUFFIX = "的领地";

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

	/**
	 * 解析玩家或组织的地区/领地显示名（不是玩家名、不是组织名）。
	 * <ol>
	 *   <li>组织且自定义地区名非空 → 用它</li>
	 *   <li>玩家且自定义地区名非空 → 用它</li>
	 *   <li>否则「{ {@link #resolve} }的领地」</li>
	 * </ol>
	 */
	public static String resolveTerritoryName(MinecraftServer server, UUID entityId) {
		if (entityId == null) {
			return "";
		}

		OrganizationData data = OrganizationData.get(server);
		Optional<OrganizationRecord> organization = data.getOrganization(entityId);
		if (organization.isPresent()) {
			String custom = organization.get().territoryName();
			if (isPresentName(custom)) {
				return custom;
			}
			return defaultTerritoryName(resolve(server, entityId));
		}

		Optional<String> stored = data.getPlayerTerritoryName(entityId);
		if (stored.isPresent() && isPresentName(stored.get())) {
			return stored.get();
		}
		return defaultTerritoryName(resolve(server, entityId));
	}

	public static String defaultTerritoryName(String entityDisplayName) {
		return (entityDisplayName == null ? "" : entityDisplayName) + DEFAULT_TERRITORY_NAME_SUFFIX;
	}

	/**
	 * 校验地区/领地显示名：去首尾空白、非空、长度上限、禁止控制字符。
	 *
	 * @throws IllegalArgumentException 名称不合法
	 */
	public static String requireValidTerritoryName(String raw) {
		if (raw == null) {
			throw new IllegalArgumentException("Territory/region name cannot be empty");
		}
		String name = raw.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException("Territory/region name cannot be empty");
		}
		if (name.length() > TERRITORY_NAME_MAX_LENGTH) {
			throw new IllegalArgumentException(
					"Territory/region name is too long (max " + TERRITORY_NAME_MAX_LENGTH + " characters)");
		}
		for (int i = 0; i < name.length(); i++) {
			if (Character.isISOControl(name.charAt(i))) {
				throw new IllegalArgumentException("Territory/region name cannot contain control characters");
			}
		}
		return name;
	}

	private static boolean isPresentName(String name) {
		return name != null && !name.isBlank();
	}
}
