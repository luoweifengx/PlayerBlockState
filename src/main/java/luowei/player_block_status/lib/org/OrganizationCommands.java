package luowei.player_block_status.lib.org;

import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.org.OrganizationService.OrganizationException;

/**
 * /pbs org 子命令：创建、邀请、接受/拒绝、离开、踢人、移交主人、查询与合并组织。
 */
public final class OrganizationCommands {
	private OrganizationCommands() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> buildOrgNode() {
		return Commands.literal("org")
				.then(Commands.literal("create")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.argument("name", StringArgumentType.greedyString())
								.executes(context -> create(context.getSource(),
										StringArgumentType.getString(context, "name")))))
				.then(Commands.literal("invite")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.argument("player", EntityArgument.player())
								.executes(context -> invite(context.getSource(),
										EntityArgument.getPlayer(context, "player")))))
				.then(Commands.literal("accept")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.executes(context -> accept(context.getSource())))
				.then(Commands.literal("deny")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.executes(context -> deny(context.getSource())))
				.then(Commands.literal("leave")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.executes(context -> leave(context.getSource())))
				.then(Commands.literal("kick")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.argument("player", EntityArgument.player())
								.executes(context -> kick(context.getSource(),
										EntityArgument.getPlayer(context, "player")))))
				.then(Commands.literal("transfer")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.argument("player", EntityArgument.player())
								.executes(context -> transfer(context.getSource(),
										EntityArgument.getPlayer(context, "player")))))
				.then(Commands.literal("info")
						.executes(context -> infoSelf(context.getSource()))
						.then(Commands.argument("orgId", UuidArgument.uuid())
								.executes(context -> infoOrg(context.getSource(),
										UuidArgument.getUuid(context, "orgId")))))
				.then(Commands.literal("merge")
						.requires(source -> source.hasPermission(2))
						.then(Commands.argument("from", UuidArgument.uuid())
								.then(Commands.argument("to", UuidArgument.uuid())
										.executes(context -> merge(context.getSource(),
												UuidArgument.getUuid(context, "from"),
												UuidArgument.getUuid(context, "to"))))));
	}

	private static int create(CommandSourceStack source, String name) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			OrganizationRecord record = OrganizationService.createOrganization(stack.getServer(), player, name);
			stack.sendSuccess(() -> Component.literal("Created organization \"" + record.name() + "\""), false);
		});
	}

	private static int invite(CommandSourceStack source, ServerPlayer target) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			OrganizationRecord record = OrganizationService.invitePlayer(stack.getServer(), player, target);
			stack.sendSuccess(() -> Component.literal("Invited " + target.getGameProfile().getName()
					+ " to organization \"" + record.name() + "\""), false);
			target.sendSystemMessage(Component.literal(
					"You have been invited to organization \"" + record.name()
							+ "\". Use /pbs org accept or /pbs org deny."));
		});
	}

	private static int accept(CommandSourceStack source) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			OrganizationRecord record = OrganizationService.acceptInvite(stack.getServer(), player);
			stack.sendSuccess(() -> Component.literal("Joined organization \"" + record.name() + "\""), false);
		});
	}

	private static int deny(CommandSourceStack source) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			OrganizationRecord record = OrganizationService.denyInvite(stack.getServer(), player);
			stack.sendSuccess(() -> Component.literal("Declined invite to organization \"" + record.name() + "\""), false);
		});
	}

	private static int leave(CommandSourceStack source) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			boolean dissolved = OrganizationService.leaveOrganization(stack.getServer(), player);
			if (dissolved) {
				stack.sendSuccess(() -> Component.literal(
						"Left and disbanded the organization. Existing territory remains with the organization account."),
						false);
			} else {
				stack.sendSuccess(() -> Component.literal(
						"Left organization. Existing org territory remains with the organization."), false);
			}
		});
	}

	private static int kick(CommandSourceStack source, ServerPlayer target) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			OrganizationService.kickMember(stack.getServer(), player, target);
			stack.sendSuccess(() -> Component.literal("Kicked " + target.getGameProfile().getName()
					+ " from the organization"), false);
			target.sendSystemMessage(Component.literal("You were kicked from the organization."));
		});
	}

	private static int transfer(CommandSourceStack source, ServerPlayer target) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			OrganizationService.transferOwnership(stack.getServer(), player, target);
			stack.sendSuccess(() -> Component.literal("Transferred organization ownership to "
					+ target.getGameProfile().getName()), false);
			target.sendSystemMessage(Component.literal("You are now the organization owner."));
		});
	}

	private static int infoSelf(CommandSourceStack source) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Players only"));
			return 0;
		}
		source.sendSuccess(() -> OrganizationService.describePlayerOrg(source.getServer(), player), false);
		return 1;
	}

	private static int infoOrg(CommandSourceStack source, UUID orgId) {
		return OrganizationService.getOrganization(source.getServer(), orgId)
				.map(record -> {
					source.sendSuccess(() -> OrganizationService.describeOrganizationPublic(source.getServer(), record), false);
					return 1;
				})
				.orElseGet(() -> {
					source.sendFailure(Component.literal("Organization not found: " + orgId));
					return 0;
				});
	}

	private static int merge(CommandSourceStack source, UUID from, UUID to) {
		return run(source, stack -> {
			OrganizationService.mergeOrganizations(stack.getServer(), from, to);
			stack.sendSuccess(() -> Component.literal("Merged organization " + from + " into " + to), true);
		});
	}

	private static int run(CommandSourceStack source, OrgAction action) {
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
	private interface OrgAction {
		void run(CommandSourceStack source) throws CommandSyntaxException;
	}
}
