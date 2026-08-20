package luowei.player_block_status.lib.org;

import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import luowei.player_block_status.lib.org.OrganizationService.OrganizationException;

/**
 * /pbs org 子命令：创建、加入、离开、查询与合并组织。
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
				.then(Commands.literal("join")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.argument("orgId", UuidArgument.uuid())
								.executes(context -> join(context.getSource(),
										UuidArgument.getUuid(context, "orgId")))))
				.then(Commands.literal("leave")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.executes(context -> leave(context.getSource())))
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
			OrganizationRecord record = OrganizationService.createOrganization(stack.getServer(), player, name.trim());
			stack.sendSuccess(() -> Component.literal("Created organization \"" + record.name()
					+ "\" with id " + record.id()), true);
		});
	}

	private static int join(CommandSourceStack source, UUID orgId) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			OrganizationService.joinOrganization(stack.getServer(), player, orgId);
			stack.sendSuccess(() -> Component.literal("Joined organization " + orgId), true);
		});
	}

	private static int leave(CommandSourceStack source) {
		return run(source, stack -> {
			ServerPlayer player = stack.getPlayerOrException();
			OrganizationService.leaveOrganization(stack.getServer(), player);
			stack.sendSuccess(() -> Component.literal("Left organization. Existing org territory remains with the organization."), true);
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
					source.sendSuccess(() -> Component.literal("Organization{name=" + record.name()
							+ ", territory=" + EntityDisplayNames.resolveTerritoryName(source.getServer(), record.id())
							+ ", id=" + record.id() + ", owner=" + record.owner()
							+ ", members=" + record.members() + "}"), false);
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
