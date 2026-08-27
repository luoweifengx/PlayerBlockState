package luowei.player_block_status.lib.advancement;

import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.api.BeaconOfferingSnapshot;
import luowei.player_block_status.lib.api.PlayerBlockStatusLib;

/**
 * 库模组进度发放。criterion 名必须与 JSON 中的 {@code granted} 一致。
 */
public final class TerritoryAdvancements {
	public static final ResourceLocation ROOT = PlayerBlockStatus.id("root");
	public static final ResourceLocation HOME = PlayerBlockStatus.id("home");
	public static final ResourceLocation NETHER_SOULS = PlayerBlockStatus.id("nether_souls");
	public static final ResourceLocation BEACON_END = PlayerBlockStatus.id("beacon_end");

	private static final String CRITERION = "granted";

	private TerritoryAdvancements() {
	}

	public static void register() {
		PlayerBlockStatusLib.addBeaconOfferingListener(TerritoryAdvancements::onBeaconOfferingChanged);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> checkJoin(handler.getPlayer()));
	}

	public static boolean shouldGrantHome(int ownedCount) {
		return ownedCount > 0;
	}

	/**
	 * 只在第一次从低于 3 跨到 {@code maxLevel >= 3} 时发放「永远终结了」。
	 */
	public static boolean shouldGrantBeaconEnd(int previousMaxLevel, int currentMaxLevel, boolean alreadyEnded) {
		return !alreadyEnded && previousMaxLevel < 3 && currentMaxLevel >= 3;
	}

	public static void checkHomeForOnlinePlayers(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			checkHome(player);
		}
	}

	public static void onFirstNetherPortalOpened(MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (TerritoryStoryData.get(server).markFirstNetherPortalOpened()) {
			awardAllOnline(server, NETHER_SOULS);
		}
	}

	public static void onBeaconOfferingChanged(
			MinecraftServer server,
			BeaconOfferingSnapshot previous,
			BeaconOfferingSnapshot current
	) {
		if (server == null || previous == null || current == null) {
			return;
		}
		TerritoryStoryData story = TerritoryStoryData.get(server);
		if (!shouldGrantBeaconEnd(previous.maxLevel(), current.maxLevel(), story.isLevel3BeaconActivated())) {
			return;
		}
		if (story.markLevel3BeaconActivated()) {
			awardAllOnline(server, BEACON_END);
		}
	}

	static void checkJoin(ServerPlayer player) {
		if (player == null || player.getServer() == null) {
			return;
		}
		MinecraftServer server = player.getServer();
		award(player, ROOT);
		checkHome(player);
		TerritoryStoryData story = TerritoryStoryData.get(server);
		if (story.isFirstNetherPortalOpened()) {
			award(player, NETHER_SOULS);
		}
		if (story.isLevel3BeaconActivated()) {
			award(player, BEACON_END);
		}
	}

	private static void checkHome(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		UUID account = PlayerBlockStatusLib.queryPlayerOrganization(server, player.getUUID())
				.orElse(player.getUUID());
		int owned = 0;
		for (ServerLevel level : server.getAllLevels()) {
			owned += PlayerBlockStatusLib.queryEntityTerritoryCounts(level, account).owned();
		}
		if (shouldGrantHome(owned)) {
			award(player, HOME);
		}
	}

	private static void awardAllOnline(MinecraftServer server, ResourceLocation id) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			award(player, id);
		}
	}

	private static void award(ServerPlayer player, ResourceLocation id) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		if (!ROOT.equals(id)) {
			awardHolder(player, server, ROOT);
		}
		awardHolder(player, server, id);
	}

	private static void awardHolder(ServerPlayer player, MinecraftServer server, ResourceLocation id) {
		AdvancementHolder holder = server.getAdvancements().get(id);
		if (holder == null) {
			return;
		}
		player.getAdvancements().award(holder, CRITERION);
	}
}
