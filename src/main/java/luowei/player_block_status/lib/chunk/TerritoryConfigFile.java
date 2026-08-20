package luowei.player_block_status.lib.chunk;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.event.TerritoryEnterMessagePrefs.InfoType;
import luowei.player_block_status.lib.org.EntityDisplayNames;

/**
 * {@code config/player-block-status.json}：领土数值与进界提示默认值。
 * 启动时读入 {@link TerritoryConfig}；缺键则补全并写回。改文件后需重启游戏生效。
 */
public final class TerritoryConfigFile {
	public static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("player-block-status.json");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String TERRITORY_COMMENT_KEY = "_comment_territory";
	private static final String TERRITORY_COMMENT =
			"Territory scoring and thresholds. Restart the game after editing. "
					+ "blockScorePerBlock: points per remaining placed block. "
					+ "occupationThreshold / naturalReturnThreshold / deathThreshold: state lines. "
					+ "stayScorePerInterval every stayTickInterval ticks; stay scores reset at daily refresh. "
					+ "occupationTakeoverMultiplier / borderTakeoverMultiplier: challenger vs current owner. "
					+ "hostileBorderExtensionChunks: Chebyshev width beyond BORDER. "
					+ "dailyRefreshTime: world day time 0-23999. "
					+ "structureClaimBlocksPerTick: structure claim budget. "
					+ "showTerritoryEnterMessage: master switch for action-bar enter text.";

	private TerritoryConfigFile() {
	}

	public static void load() {
		if (!Files.isRegularFile(FILE)) {
			JsonObject json = new JsonObject();
			writeTerritoryKeys(json);
			write(json);
			PlayerBlockStatus.LOGGER.info("Wrote default territory config to {}", FILE);
			return;
		}
		JsonObject json = tryRead();
		if (json == null) {
			PlayerBlockStatus.LOGGER.warn("Keeping {}, using built-in territory defaults", FILE);
			return;
		}
		apply(json);
		writeTerritoryKeys(json);
		write(json);
		PlayerBlockStatus.LOGGER.info("Loaded territory config from {}", FILE);
	}

	/** 文件不存在时返回空对象；解析失败返回 {@code null}（调用方不应覆盖原文件）。 */
	public static JsonObject tryRead() {
		if (!Files.isRegularFile(FILE)) {
			return new JsonObject();
		}
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			JsonElement parsed = JsonParser.parseReader(reader);
			if (parsed != null && parsed.isJsonObject()) {
				return parsed.getAsJsonObject();
			}
			PlayerBlockStatus.LOGGER.warn("{} is not a JSON object", FILE);
		} catch (Exception exception) {
			PlayerBlockStatus.LOGGER.warn("Failed to read {}", FILE, exception);
		}
		return null;
	}

	public static void write(JsonObject json) {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(json, writer);
			}
		} catch (IOException exception) {
			PlayerBlockStatus.LOGGER.warn("Failed to write {}", FILE, exception);
		}
	}

	private static void apply(JsonObject json) {
		TerritoryConfig.occupationThreshold = readInt(json, "occupationThreshold", TerritoryConfig.occupationThreshold);
		TerritoryConfig.naturalReturnThreshold = readInt(json, "naturalReturnThreshold", TerritoryConfig.naturalReturnThreshold);
		TerritoryConfig.deathThreshold = readInt(json, "deathThreshold", TerritoryConfig.deathThreshold);
		TerritoryConfig.blockScorePerBlock = readInt(json, "blockScorePerBlock", TerritoryConfig.blockScorePerBlock);
		TerritoryConfig.stayScorePerInterval = readInt(json, "stayScorePerInterval", TerritoryConfig.stayScorePerInterval);
		TerritoryConfig.stayTickInterval = readIntAtLeast(json, "stayTickInterval", TerritoryConfig.stayTickInterval, 1);
		TerritoryConfig.deathPenalty = readInt(json, "deathPenalty", TerritoryConfig.deathPenalty);
		TerritoryConfig.deathRecoveryPerDay = readInt(json, "deathRecoveryPerDay", TerritoryConfig.deathRecoveryPerDay);
		TerritoryConfig.occupationTakeoverMultiplier = readPositiveDouble(
				json, "occupationTakeoverMultiplier", TerritoryConfig.occupationTakeoverMultiplier);
		TerritoryConfig.borderTakeoverMultiplier = readPositiveDouble(
				json, "borderTakeoverMultiplier", TerritoryConfig.borderTakeoverMultiplier);
		TerritoryConfig.hostileBorderExtensionChunks = readIntAtLeast(
				json, "hostileBorderExtensionChunks", TerritoryConfig.hostileBorderExtensionChunks, 0);
		TerritoryConfig.dailyRefreshTime = readIntRange(
				json, "dailyRefreshTime", TerritoryConfig.dailyRefreshTime, 0, 23999);
		TerritoryConfig.structureClaimBlocksPerTick = readIntAtLeast(
				json, "structureClaimBlocksPerTick", TerritoryConfig.structureClaimBlocksPerTick, 1);
		TerritoryConfig.showTerritoryEnterMessage = readBoolean(
				json, "showTerritoryEnterMessage", TerritoryConfig.showTerritoryEnterMessage);

		if (json.has("ownTerritoryEnterMessage")) {
			try {
				TerritoryConfig.ownTerritoryEnterMessage = EntityDisplayNames.requireValidTerritoryName(
						json.get("ownTerritoryEnterMessage").getAsString());
			} catch (RuntimeException ignored) {
			}
		}
		if (json.has("enterMessageInfoType")) {
			TerritoryConfig.enterMessageInfoType = InfoType.fromIdOrDefault(
					json.get("enterMessageInfoType").getAsString()).id();
		}
	}

	private static void writeTerritoryKeys(JsonObject json) {
		json.addProperty(TERRITORY_COMMENT_KEY, TERRITORY_COMMENT);
		json.addProperty("occupationThreshold", TerritoryConfig.occupationThreshold);
		json.addProperty("naturalReturnThreshold", TerritoryConfig.naturalReturnThreshold);
		json.addProperty("deathThreshold", TerritoryConfig.deathThreshold);
		json.addProperty("blockScorePerBlock", TerritoryConfig.blockScorePerBlock);
		json.addProperty("stayScorePerInterval", TerritoryConfig.stayScorePerInterval);
		json.addProperty("stayTickInterval", TerritoryConfig.stayTickInterval);
		json.addProperty("deathPenalty", TerritoryConfig.deathPenalty);
		json.addProperty("deathRecoveryPerDay", TerritoryConfig.deathRecoveryPerDay);
		json.addProperty("occupationTakeoverMultiplier", TerritoryConfig.occupationTakeoverMultiplier);
		json.addProperty("borderTakeoverMultiplier", TerritoryConfig.borderTakeoverMultiplier);
		json.addProperty("hostileBorderExtensionChunks", TerritoryConfig.hostileBorderExtensionChunks);
		json.addProperty("dailyRefreshTime", TerritoryConfig.dailyRefreshTime);
		json.addProperty("structureClaimBlocksPerTick", TerritoryConfig.structureClaimBlocksPerTick);
		json.addProperty("showTerritoryEnterMessage", TerritoryConfig.showTerritoryEnterMessage);
		json.addProperty("ownTerritoryEnterMessage", TerritoryConfig.ownTerritoryEnterMessage);
		json.addProperty("enterMessageInfoType", TerritoryConfig.enterMessageInfoType);
	}

	private static int readInt(JsonObject json, String key, int fallback) {
		if (!json.has(key)) {
			return fallback;
		}
		try {
			return json.get(key).getAsInt();
		} catch (RuntimeException exception) {
			PlayerBlockStatus.LOGGER.warn("Invalid {} in {}, using {}", key, FILE, fallback);
			return fallback;
		}
	}

	private static int readIntAtLeast(JsonObject json, String key, int fallback, int min) {
		int value = readInt(json, key, fallback);
		if (value < min) {
			PlayerBlockStatus.LOGGER.warn("Invalid {}={} in {}, using {}", key, value, FILE, fallback);
			return fallback;
		}
		return value;
	}

	private static int readIntRange(JsonObject json, String key, int fallback, int min, int max) {
		int value = readInt(json, key, fallback);
		if (value < min || value > max) {
			PlayerBlockStatus.LOGGER.warn("Invalid {}={} in {}, using {}", key, value, FILE, fallback);
			return fallback;
		}
		return value;
	}

	private static double readPositiveDouble(JsonObject json, String key, double fallback) {
		if (!json.has(key)) {
			return fallback;
		}
		try {
			double value = json.get(key).getAsDouble();
			if (value > 0.0d) {
				return value;
			}
		} catch (RuntimeException ignored) {
		}
		PlayerBlockStatus.LOGGER.warn("Invalid {} in {}, using {}", key, FILE, fallback);
		return fallback;
	}

	private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
		if (!json.has(key)) {
			return fallback;
		}
		try {
			return json.get(key).getAsBoolean();
		} catch (RuntimeException exception) {
			PlayerBlockStatus.LOGGER.warn("Invalid {} in {}, using {}", key, FILE, fallback);
			return fallback;
		}
	}
}
