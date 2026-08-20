package luowei.player_block_status.lib.debug;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.lib.api.PlayerBlockStatusLib;
import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.ChunkTerritoryAccess;
import luowei.player_block_status.lib.chunk.ChunkTerritoryData;
import luowei.player_block_status.lib.chunk.RegionManager;
import luowei.player_block_status.lib.chunk.TerritoryConfig;
import luowei.player_block_status.lib.chunk.TerritoryDailyProcessor.ScheduleAttempt;
import luowei.player_block_status.lib.chunk.TerritoryDailyProcessor.ScheduleResult;

/**
 * 调试指令：强制改写区块状态与归属。
 *
 * <pre>
 * /pbs set here &lt;radius&gt; &lt;state|keep&gt;
 * /pbs set here &lt;radius&gt; &lt;state|keep&gt; none
 * /pbs set here &lt;radius&gt; &lt;state|keep&gt; &lt;ownerUuid&gt;
 * /pbs set here &lt;radius&gt; &lt;state|keep&gt; &lt;player&gt;
 * /pbs set at &lt;chunkX&gt; &lt;chunkZ&gt; &lt;radius&gt; &lt;state|keep&gt; [none|uuid|player]
 * /pbs sentinel
 * /pbs sentinel at &lt;chunkX&gt; &lt;chunkZ&gt;
 * </pre>
 *
 * {@code keep} 表示不修改该项；{@code none} 表示清空归属。
 */
public final class ChunkForceCommands {
	private static final int RADIUS_MAX = 64;
	private static final SimpleCommandExceptionType INVALID_STATE =
			new SimpleCommandExceptionType(Component.literal(
					"Unknown chunk state (use NATURAL/OCCUPIED/BORDER/... or keep)"));
	private static final SimpleCommandExceptionType NOTHING_TO_CHANGE =
			new SimpleCommandExceptionType(Component.literal(
					"Nothing to change: state is keep and owner omitted"));

	private static final SuggestionProvider<CommandSourceStack> STATE_SUGGESTIONS = (context, builder) ->
			SharedSuggestionProvider.suggest(
					Stream.concat(
							Stream.of("keep"),
							Arrays.stream(ChunkState.values()).map(state -> state.name().toLowerCase(Locale.ROOT))
					),
					builder
			);

	private ChunkForceCommands() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> buildRefreshNode() {
		return Commands.literal("refresh")
				.requires(source -> source.hasPermission(2))
				.executes(ctx -> runRefresh(ctx));
	}

	public static LiteralArgumentBuilder<CommandSourceStack> buildSentinelNode() {
		return Commands.literal("sentinel")
				.requires(source -> source.hasPermission(2))
				.executes(ctx -> runSentinel(ctx, true))
				.then(Commands.literal("at")
						.then(Commands.argument("chunkX", IntegerArgumentType.integer())
								.then(Commands.argument("chunkZ", IntegerArgumentType.integer())
										.executes(ctx -> runSentinel(ctx, false)))));
	}

	public static LiteralArgumentBuilder<CommandSourceStack> buildSetNode() {
		return Commands.literal("set")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("here")
						.then(Commands.argument("radius", IntegerArgumentType.integer(0, RADIUS_MAX))
								.then(stateOwnerTail(true))))
				.then(Commands.literal("at")
						.then(Commands.argument("chunkX", IntegerArgumentType.integer())
								.then(Commands.argument("chunkZ", IntegerArgumentType.integer())
										.then(Commands.argument("radius", IntegerArgumentType.integer(0, RADIUS_MAX))
												.then(stateOwnerTail(false))))));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> stateOwnerTail(boolean here) {
		return Commands.argument("state", StringArgumentType.word())
				.suggests(STATE_SUGGESTIONS)
				.executes(ctx -> run(ctx, here, false, null))
				.then(Commands.literal("none")
						.executes(ctx -> run(ctx, here, true, null)))
				.then(Commands.argument("ownerUuid", UuidArgument.uuid())
						.executes(ctx -> run(ctx, here, true, UuidArgument.getUuid(ctx, "ownerUuid"))))
				.then(Commands.argument("ownerPlayer", EntityArgument.player())
						.executes(ctx -> run(ctx, here, true,
								EntityArgument.getPlayer(ctx, "ownerPlayer").getUUID())));
	}

	private static int runSentinel(CommandContext<CommandSourceStack> ctx, boolean here) {
		ChunkPos chunkPos = resolveCenter(ctx, here);
		ServerLevel level = ctx.getSource().getLevel();
		ChunkTerritoryData data = ChunkTerritoryAccess.getIfPresent(level, chunkPos);
		int placedTotal = 0;
		int sentinel = 0;
		if (data != null) {
			placedTotal = data.getPlacedBlocks().size();
			for (UUID owner : data.getPlacedBlocks().values()) {
				if (TerritoryConfig.isStructureSentinel(owner)) {
					sentinel++;
				}
			}
		}
		int reportedSentinel = sentinel;
		int reportedTotal = placedTotal;
		ctx.getSource().sendSuccess(
				() -> Component.literal(String.format(
						"chunk %s sentinel=%d placedTotal=%d",
						chunkPos,
						reportedSentinel,
						reportedTotal
				)),
				false
		);
		return 1;
	}

	private static int runRefresh(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		ScheduleAttempt attempt = RegionManager.forceDailyRefresh(
				level,
				PlayerBlockStatusLib.getOrganizationProvider(),
				PlayerBlockStatusLib.getSafeBiomeChecker()
		);

		if (attempt.result() == ScheduleResult.SCHEDULED) {
			ctx.getSource().sendSuccess(
					() -> Component.literal(String.format(
							"Scheduled territory refresh for %d chunk(s) in %s",
							attempt.chunkCount(),
							level.dimension().location()
					)),
					true
			);
			return 1;
		}

		String message = switch (attempt.result()) {
			case NOTHING_TO_RECOMPUTE -> "Nothing to recompute (no dirty or active chunks)";
			case REFRESH_ALREADY_IN_PROGRESS -> "Daily refresh already in progress";
			default -> "Territory refresh was not scheduled";
		};
		ctx.getSource().sendFailure(Component.literal(message));
		return 0;
	}

	private static int run(
			CommandContext<CommandSourceStack> ctx,
			boolean here,
			boolean updateOwner,
			UUID owner
	) throws CommandSyntaxException {
		String stateRaw = StringArgumentType.getString(ctx, "state");
		boolean updateState = !stateRaw.equalsIgnoreCase("keep");
		ChunkState state = updateState ? parseState(stateRaw) : null;
		if (!updateState && !updateOwner) {
			throw NOTHING_TO_CHANGE.create();
		}

		int radius = IntegerArgumentType.getInteger(ctx, "radius");
		ChunkPos center = resolveCenter(ctx, here);
		ServerLevel level = ctx.getSource().getLevel();

		int changed = RegionManager.forceSetChunks(level, center, radius, state, updateOwner, owner);
		String stateText = updateState ? state.name() : "(unchanged)";
		String ownerText = !updateOwner ? "(unchanged)" : (owner == null ? "none" : owner.toString());
		ctx.getSource().sendSuccess(
				() -> Component.literal(String.format(
						"Forced %d chunk(s): center=%s radius=%d state=%s owner=%s",
						changed, center, radius, stateText, ownerText
				)),
				true
		);
		return Math.max(changed, 1);
	}

	private static ChunkPos resolveCenter(CommandContext<CommandSourceStack> ctx, boolean here) {
		if (!here) {
			return new ChunkPos(
					IntegerArgumentType.getInteger(ctx, "chunkX"),
					IntegerArgumentType.getInteger(ctx, "chunkZ")
			);
		}
		if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
			return player.chunkPosition();
		}
		return new ChunkPos(ctx.getSource().getLevel().getSharedSpawnPos());
	}

	private static ChunkState parseState(String raw) throws CommandSyntaxException {
		try {
			return ChunkState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw INVALID_STATE.create();
		}
	}
}
