package luowei.player_block_status.lib.event;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.chunk.TerritoryConfig;
import luowei.player_block_status.lib.net.TerritoryEnterPrefsPayload;
import luowei.player_block_status.lib.org.EntityDisplayNames;
import luowei.player_block_status.lib.org.OrganizationService.OrganizationException;

/**
 * 每位玩家进入领地时的提示文案与 infotype（off / sight of me / sight of others）。
 * 客户端配置是持久化来源；进服同步到此表，指令改完后再写回客户端。
 */
public final class TerritoryEnterMessagePrefs {
	private static final Map<UUID, Settings> PLAYERS = new ConcurrentHashMap<>();

	private TerritoryEnterMessagePrefs() {
	}

	public static Settings get(UUID playerId) {
		Settings stored = PLAYERS.get(playerId);
		return stored != null ? stored : Settings.fromTerritoryConfig();
	}

	public static void put(UUID playerId, Settings settings) {
		if (playerId == null || settings == null) {
			return;
		}
		PLAYERS.put(playerId, settings);
	}

	public static void remove(UUID playerId) {
		if (playerId != null) {
			PLAYERS.remove(playerId);
		}
	}

	public static Settings withOwnMessage(UUID playerId, String rawMessage) {
		Settings current = get(playerId);
		Settings updated = new Settings(requireMessage(rawMessage), current.infoType());
		put(playerId, updated);
		return updated;
	}

	public static Settings withInfoType(UUID playerId, InfoType infoType) {
		Settings current = get(playerId);
		Settings updated = new Settings(current.ownEnterMessage(), infoType);
		put(playerId, updated);
		return updated;
	}

	public static void syncToClient(ServerPlayer player, Settings settings) {
		if (!ServerPlayNetworking.canSend(player, TerritoryEnterPrefsPayload.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(player, new TerritoryEnterPrefsPayload(
				settings.ownEnterMessage(),
				settings.infoType().id()
		));
	}

	public static String requireMessage(String raw) {
		try {
			return EntityDisplayNames.requireValidTerritoryName(raw);
		} catch (IllegalArgumentException exception) {
			throw new OrganizationException(exception.getMessage());
		}
	}

	public static boolean showsEnterMessage(UUID playerId) {
		return get(playerId).infoType().showsEnterMessage();
	}

	public static String formatOwnEnter(ServerPlayer player, String publicTerritoryName) {
		Settings settings = get(player.getUUID());
		if (settings.infoType() == InfoType.SIGHT_OF_OTHERS) {
			return publicTerritoryName;
		}
		return settings.ownEnterMessage();
	}

	public record Settings(String ownEnterMessage, InfoType infoType) {
		public static Settings fromTerritoryConfig() {
			return new Settings(
					TerritoryConfig.ownTerritoryEnterMessage,
					InfoType.fromIdOrDefault(TerritoryConfig.enterMessageInfoType)
			);
		}
	}

	public enum InfoType {
		OFF("off"),
		SIGHT_OF_ME("sight of me", "me"),
		SIGHT_OF_OTHERS("sight of others", "others");

		private static final String ALLOWED =
				"off, sight of me, or sight of others";

		private final String id;
		private final String[] aliases;

		InfoType(String id, String... aliases) {
			this.id = id;
			this.aliases = aliases;
		}

		public String id() {
			return id;
		}

		public boolean showsEnterMessage() {
			return this != OFF;
		}

		public String describe() {
			return switch (this) {
				case OFF -> "hide enter-territory messages";
				case SIGHT_OF_ME -> "show your own-land enter message";
				case SIGHT_OF_OTHERS -> "show the public region name that other players see";
			};
		}

		public static InfoType fromId(String raw) {
			if (raw == null || raw.isBlank()) {
				throw new OrganizationException("infotype must be " + ALLOWED);
			}
			String id = raw.trim().toLowerCase(Locale.ROOT);
			for (InfoType type : values()) {
				if (type.id.equals(id)) {
					return type;
				}
				for (String alias : type.aliases) {
					if (alias.equals(id)) {
						return type;
					}
				}
			}
			throw new OrganizationException("infotype must be " + ALLOWED);
		}

		public static InfoType fromIdOrDefault(String raw) {
			try {
				return fromId(raw);
			} catch (OrganizationException ignored) {
				return SIGHT_OF_ME;
			}
		}
	}
}
