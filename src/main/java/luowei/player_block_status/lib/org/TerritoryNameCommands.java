package luowei.player_block_status.lib.org;

import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.event.TerritoryEnterMessagePrefs;
import luowei.player_block_status.lib.org.OrganizationService.OrganizationException;

/**
 * /pbs territory 子命令：改地区/领地显示名（不是玩家名、不是组织名），
 * 以及进入自己领地时的提示文案 / infotype。
 * <p>
 * 玩家与组织各自有独立字段。加入组织后个人字段仍存在，但进领地提示读区块
 * occupyingOrg（可能是组织 UUID），因此个人改名不会覆盖组织领地的进入提示。
 */
public final class TerritoryNameCommands {
	private TerritoryNameCommands() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> buildTerritoryNode() {
		return Commands.literal("territory")
				.then(Commands.literal("info")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.executes(context -> infoSelf(context.getSource())))
				.then(Commands.literal("mine")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.argument("territoryName", StringArgumentType.greedyString())
								.executes(context -> renameOwnPersonal(
										context.getSource(),
										StringArgumentType.getString(context, "territoryName")))))
				.then(Commands.literal("org")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.argument("territoryName", StringArgumentType.greedyString())
								.executes(context -> renameOwnOrganization(
										context.getSource(),
										StringArgumentType.getString(context, "territoryName")))))
				.then(Commands.literal("backmine")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.argument("message", StringArgumentType.greedyString())
								.executes(context -> setOwnEnterMessage(
										context.getSource(),
										StringArgumentType.getString(context, "message")))))
				.then(Commands.literal("infotype")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.literal("off")
								.executes(context -> setInfoType(context.getSource(), TerritoryEnterMessagePrefs.InfoType.OFF)))
						.then(Commands.literal("sight")
								.then(Commands.literal("of")
										.then(Commands.literal("me")
												.executes(context -> setInfoType(
														context.getSource(), TerritoryEnterMessagePrefs.InfoType.SIGHT_OF_ME)))
										.then(Commands.literal("others")
												.executes(context -> setInfoType(
														context.getSource(), TerritoryEnterMessagePrefs.InfoType.SIGHT_OF_OTHERS))))))
				.then(Commands.literal("player")
						.requires(source -> source.hasPermission(2))
						.then(Commands.argument("target", EntityArgument.player())
								.then(Commands.argument("territoryName", StringArgumentType.greedyString())
										.executes(context -> renameTargetPlayer(
												context.getSource(),
												EntityArgument.getPlayer(context, "target"),
												StringArgumentType.getString(context, "territoryName"))))))
				.then(Commands.literal("organization")
						.requires(source -> source.hasPermission(2))
						.then(Commands.argument("orgId", UuidArgument.uuid())
								.then(Commands.argument("territoryName", StringArgumentType.greedyString())
										.executes(context -> renameTargetOrganization(
												context.getSource(),
												UuidArgument.getUuid(context, "orgId"),
												StringArgumentType.getString(context, "territoryName"))))));
	}

	private static int infoSelf(CommandSourceStack source) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Players only"));
			return 0;
		}
		MinecraftServer server = source.getServer();
		String personal = EntityDisplayNames.resolveTerritoryName(server, player.getUUID());
		Optional<UUID> orgId = OrganizationData.get(server).getPlayerOrganization(player.getUUID());
		String orgLine = orgId
				.map(id -> "Organization territory/region name: \""
						+ EntityDisplayNames.resolveTerritoryName(server, id)
						+ "\" (org id " + id + "). Enter-territory messages use occupyingOrg.")
				.orElse("Not in an organization; enter-territory messages use your personal territory/region name.");
		TerritoryEnterMessagePrefs.Settings enterPrefs = TerritoryEnterMessagePrefs.get(player.getUUID());
		String enterLine = " Own-land enter message: \"" + enterPrefs.ownEnterMessage()
				+ "\" (infotype=" + enterPrefs.infoType().id()
				+ "; off=hide, sight of me=that text, sight of others=public region name).";
		source.sendSuccess(() -> Component.literal(
				"Personal territory/region name: \"" + personal + "\" (not your player name). " + orgLine + enterLine
		), false);
		return 1;
	}

	private static int setOwnEnterMessage(CommandSourceStack source, String rawMessage) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			TerritoryEnterMessagePrefs.Settings settings =
					TerritoryEnterMessagePrefs.withOwnMessage(player.getUUID(), rawMessage);
			TerritoryEnterMessagePrefs.syncToClient(player, settings);
			stack.sendSuccess(() -> Component.literal(
					"Own-land enter message set to \"" + settings.ownEnterMessage()
							+ "\" (saved to client config; default is 自己的领地)"
			), true);
		});
	}

	private static int setInfoType(CommandSourceStack source, TerritoryEnterMessagePrefs.InfoType infoType) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			TerritoryEnterMessagePrefs.Settings settings =
					TerritoryEnterMessagePrefs.withInfoType(player.getUUID(), infoType);
			TerritoryEnterMessagePrefs.syncToClient(player, settings);
			stack.sendSuccess(() -> Component.literal(
					"Enter-message infotype set to " + infoType.id() + " (" + infoType.describe()
							+ "; saved to client config)"
			), true);
		});
	}

	private static int renameOwnPersonal(CommandSourceStack source, String rawName) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			MinecraftServer server = stack.getServer();
			String oldName = EntityDisplayNames.resolveTerritoryName(server, player.getUUID());
			String newName = OrganizationService.setPlayerTerritoryName(server, player.getUUID(), rawName);
			boolean inOrg = OrganizationData.get(server).getPlayerOrganization(player.getUUID()).isPresent();
			String note = inOrg
					? " You are in an organization; enter-territory messages still show the organization's region name (occupyingOrg), not this personal field."
					: "";
			stack.sendSuccess(() -> Component.literal(
					"Changed your personal territory/region name (not your player name): \""
							+ oldName + "\" → \"" + newName + "\"." + note
			), true);
		});
	}

	private static int renameOwnOrganization(CommandSourceStack source, String rawName) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			MinecraftServer server = stack.getServer();
			OrganizationData data = OrganizationData.get(server);
			UUID orgId = data.getPlayerOrganization(player.getUUID()).orElseThrow(
					() -> new OrganizationException("You are not in an organization. Use /pbs territory mine to rename your personal region.")
			);
			OrganizationRecord record = data.getOrganization(orgId).orElseThrow(
					() -> new OrganizationException("Organization not found: " + orgId)
			);
			if (!player.getUUID().equals(record.owner()) && !stack.hasPermission(2)) {
				throw new OrganizationException(
						"Only the organization owner (or OP) can change the organization's territory/region name");
			}
			String oldName = EntityDisplayNames.resolveTerritoryName(server, orgId);
			String newName = OrganizationService.setOrganizationTerritoryName(server, orgId, rawName);
			stack.sendSuccess(() -> Component.literal(
					"Changed organization territory/region name (not the organization name): \""
							+ oldName + "\" → \"" + newName + "\" (org id " + orgId + ")"
			), true);
		});
	}

	private static int renameTargetPlayer(CommandSourceStack source, ServerPlayer target, String rawName) {
		return run(source, stack -> {
			MinecraftServer server = stack.getServer();
			String oldName = EntityDisplayNames.resolveTerritoryName(server, target.getUUID());
			String newName = OrganizationService.setPlayerTerritoryName(server, target.getUUID(), rawName);
			stack.sendSuccess(() -> Component.literal(
					"Changed personal territory/region name for " + target.getGameProfile().getName()
							+ " (not their player name): \"" + oldName + "\" → \"" + newName + "\""
			), true);
		});
	}

	private static int renameTargetOrganization(CommandSourceStack source, UUID orgId, String rawName) {
		return run(source, stack -> {
			MinecraftServer server = stack.getServer();
			String oldName = EntityDisplayNames.resolveTerritoryName(server, orgId);
			String newName = OrganizationService.setOrganizationTerritoryName(server, orgId, rawName);
			stack.sendSuccess(() -> Component.literal(
					"Changed organization territory/region name (not the organization name): \""
							+ oldName + "\" → \"" + newName + "\" (org id " + orgId + ")"
			), true);
		});
	}

	private static int run(CommandSourceStack source, TerritoryAction action) {
		try {
			action.run(source);
			return 1;
		} catch (OrganizationException exception) {
			source.sendFailure(Component.literal(exception.getMessage()));
			return 0;
		} catch (CommandSyntaxException exception) {
			source.sendFailure(Component.literal(exception.getMessage()));
			return 0;
		}
	}

	@FunctionalInterface
	private interface TerritoryAction {
		void run(CommandSourceStack source) throws CommandSyntaxException;
	}
}
