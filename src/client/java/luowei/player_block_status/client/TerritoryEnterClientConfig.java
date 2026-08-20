package luowei.player_block_status.client;

import com.google.gson.JsonObject;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.chunk.TerritoryConfig;
import luowei.player_block_status.lib.chunk.TerritoryConfigFile;
import luowei.player_block_status.lib.event.TerritoryEnterMessagePrefs.InfoType;
import luowei.player_block_status.lib.org.EntityDisplayNames;

/**
 * 与 {@link TerritoryConfigFile} 共用 {@code config/player-block-status.json}。
 * 只读写进界提示字段，不覆盖领土数值。
 */
public final class TerritoryEnterClientConfig {
	private static TerritoryEnterClientConfig instance;

	private String ownTerritoryEnterMessage = TerritoryConfig.ownTerritoryEnterMessage;
	private String enterMessageInfoType = InfoType.fromIdOrDefault(TerritoryConfig.enterMessageInfoType).id();

	private TerritoryEnterClientConfig() {
	}

	public static TerritoryEnterClientConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** 进服前重新读盘，使游戏运行中改过的配置文件能生效。 */
	public static TerritoryEnterClientConfig reload() {
		instance = load();
		return instance;
	}

	public String ownTerritoryEnterMessage() {
		return ownTerritoryEnterMessage;
	}

	public String enterMessageInfoType() {
		return enterMessageInfoType;
	}

	public void apply(String message, String infoType) {
		try {
			this.ownTerritoryEnterMessage = EntityDisplayNames.requireValidTerritoryName(message);
		} catch (IllegalArgumentException exception) {
			PlayerBlockStatus.LOGGER.warn("Ignored invalid own-land enter message from server: {}", exception.getMessage());
			return;
		}
		this.enterMessageInfoType = InfoType.fromIdOrDefault(infoType).id();
		save();
	}

	public static TerritoryEnterClientConfig load() {
		TerritoryEnterClientConfig config = new TerritoryEnterClientConfig();
		JsonObject json = TerritoryConfigFile.tryRead();
		if (json == null) {
			return config;
		}
		if (json.has("ownTerritoryEnterMessage")) {
			try {
				config.ownTerritoryEnterMessage = EntityDisplayNames.requireValidTerritoryName(
						json.get("ownTerritoryEnterMessage").getAsString());
			} catch (RuntimeException ignored) {
			}
		}
		if (json.has("enterMessageInfoType")) {
			config.enterMessageInfoType = InfoType.fromIdOrDefault(
					json.get("enterMessageInfoType").getAsString()).id();
		}
		return config;
	}

	public void save() {
		JsonObject json = TerritoryConfigFile.tryRead();
		if (json == null) {
			PlayerBlockStatus.LOGGER.warn("Skipped writing enter-message prefs; {} is unreadable", TerritoryConfigFile.FILE);
			return;
		}
		json.addProperty("_comment",
				"ownTerritoryEnterMessage: text when entering your own land (default 自己的领地). "
						+ "enterMessageInfoType: off (hide all enter messages), "
						+ "sight of me (that text), or sight of others (the public region name other players see).");
		json.addProperty("ownTerritoryEnterMessage", ownTerritoryEnterMessage);
		json.addProperty("enterMessageInfoType", enterMessageInfoType);
		TerritoryConfigFile.write(json);
	}
}
