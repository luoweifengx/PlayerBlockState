package luowei.player_block_status.lib.debug;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.ChunkTerritoryData;
import luowei.player_block_status.lib.chunk.WorldRegionData;
import luowei.player_block_status.lib.org.EntityDisplayNames;

/**
 * 将区块状态渲染为 PNG 调试地图。
 */
public final class ChunkDebugMapRenderer {
	private static final int PIXELS_PER_CHUNK = 4;
	private static final Color OWNER_UNCLAIMED = Color.WHITE;
	private static final Color OWNER_DEATH = Color.BLACK;
	/** 可区分色板；邻接 owner 用图着色取不同下标，用尽后再按黄金角生成。 */
	private static final Color[] OWNER_PALETTE = {
			new Color(31, 119, 180),
			new Color(255, 127, 14),
			new Color(44, 160, 44),
			new Color(214, 39, 40),
			new Color(148, 103, 189),
			new Color(140, 86, 75),
			new Color(227, 119, 194),
			new Color(188, 189, 34),
			new Color(23, 190, 207),
			new Color(174, 199, 232),
			new Color(255, 187, 120),
			new Color(152, 223, 138),
			new Color(255, 152, 150),
			new Color(197, 176, 213),
			new Color(196, 156, 148),
			new Color(247, 182, 210),
			new Color(219, 219, 141),
			new Color(158, 218, 229),
			new Color(57, 59, 121),
			new Color(99, 121, 57),
			new Color(140, 109, 49),
			new Color(132, 60, 57),
			new Color(123, 65, 115),
			new Color(0, 128, 128)
	};
	private static final int[] CARDINAL_DX = {1, -1, 0, 0};
	private static final int[] CARDINAL_DZ = {0, 0, 1, -1};

	private ChunkDebugMapRenderer() {
	}

	public record ChunkMapCell(ChunkState state, UUID occupyingOrg) {
		public static ChunkMapCell natural() {
			return new ChunkMapCell(ChunkState.NATURAL, null);
		}
	}

	public static Map<Long, ChunkState> collectChunkStates(ServerLevel level, ChunkPos center, int radiusChunks) {
		return collectChunkStates(level, center, radiusChunks, null);
	}

	public static Map<Long, ChunkState> collectChunkStates(
			ServerLevel level,
			ChunkPos center,
			int radiusChunks,
			MapExportTrace trace
	) {
		return toStates(collectChunkCells(level, center, radiusChunks, trace));
	}

	public static Map<Long, ChunkMapCell> collectChunkCells(ServerLevel level, ChunkPos center, int radiusChunks) {
		return collectChunkCells(level, center, radiusChunks, null);
	}

	public static Map<Long, ChunkMapCell> collectChunkCells(
			ServerLevel level,
			ChunkPos center,
			int radiusChunks,
			MapExportTrace trace
	) {
		if (trace != null) {
			trace.step("collectChunkCells begin, center=%s radius=%d", center, radiusChunks);
		}

		long regionLoadStart = System.nanoTime();
		WorldRegionData data = WorldRegionData.getForMapExport(level, trace);
		if (trace != null) {
			trace.step("WorldRegionData ready in %dms", (System.nanoTime() - regionLoadStart) / 1_000_000L);
		}

		int span = radiusChunks * 2 + 1;
		int total = span * span;
		Map<Long, ChunkMapCell> cells = HashMap.newHashMap(total);
		int queried = 0;
		int found = 0;
		long slowQueryThresholdMs = 50L;

		for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
			for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
				ChunkPos chunkPos = new ChunkPos(center.x + dx, center.z + dz);
				long key = chunkPos.toLong();
				long queryStart = System.nanoTime();
				Optional<ChunkTerritoryData> chunk = data.queryChunk(chunkPos);
				long queryMs = (System.nanoTime() - queryStart) / 1_000_000L;
				queried++;
				if (chunk.isPresent()) {
					found++;
				}
				if (trace != null && queryMs >= slowQueryThresholdMs) {
					trace.step("slow queryChunk %s took %dms found=%s", chunkPos, queryMs, chunk.isPresent());
				}
				ChunkMapCell cell = chunk
						.map(value -> new ChunkMapCell(value.getState(), value.getOccupyingOrg()))
						.orElse(ChunkMapCell.natural());
				cells.put(key, cell);
				if (trace != null && (queried % 100 == 0 || queried == total)) {
					trace.step("query progress %d/%d (found=%d)", queried, total, found);
				}
			}
		}

		if (trace != null) {
			trace.step("collectChunkCells done, queried=%d found=%d", queried, found);
		}
		return cells;
	}

	public static Map<Long, ChunkState> toStates(Map<Long, ChunkMapCell> cells) {
		Map<Long, ChunkState> states = HashMap.newHashMap(cells.size());
		cells.forEach((key, cell) -> states.put(key, cell.state()));
		return states;
	}

	public static Map<UUID, String> resolveOwnerNames(MinecraftServer server, Map<Long, ChunkMapCell> cells) {
		Map<UUID, String> names = new HashMap<>();
		for (ChunkMapCell cell : cells.values()) {
			if (cell.state().isOccupiedFamily() && cell.occupyingOrg() != null) {
				names.computeIfAbsent(cell.occupyingOrg(), id -> EntityDisplayNames.resolve(server, id));
			}
		}
		return names;
	}

	public static Path render(ServerLevel level, ChunkPos center, int radiusChunks, Path outputPath) {
		return renderFromStates(collectChunkStates(level, center, radiusChunks), center, radiusChunks, outputPath);
	}

	public static Path renderOwnerMap(ServerLevel level, ChunkPos center, int radiusChunks, Path outputPath) {
		Map<Long, ChunkMapCell> cells = collectChunkCells(level, center, radiusChunks);
		return renderOwnerMapFromCells(cells, center, radiusChunks, outputPath, resolveOwnerNames(level.getServer(), cells), null);
	}

	public static Path renderFromStates(
			Map<Long, ChunkState> states,
			ChunkPos center,
			int radiusChunks,
			Path outputPath
	) {
		return renderFromStates(states, center, radiusChunks, outputPath, null);
	}

	public static Path renderFromStates(
			Map<Long, ChunkState> states,
			ChunkPos center,
			int radiusChunks,
			Path outputPath,
			MapExportTrace trace
	) {
		if (trace != null) {
			trace.step("renderFromStates begin, pixels=%dx%d",
					(radiusChunks * 2 + 1) * PIXELS_PER_CHUNK,
					(radiusChunks * 2 + 1) * PIXELS_PER_CHUNK);
		}
		int size = radiusChunks * 2 + 1;
		BufferedImage image = new BufferedImage(size * PIXELS_PER_CHUNK, size * PIXELS_PER_CHUNK, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();

		for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
			for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
				long key = ChunkPos.asLong(center.x + dx, center.z + dz);
				ChunkState state = states.getOrDefault(key, ChunkState.NATURAL);
				Color color = colorForState(state);
				int pixelX = (dx + radiusChunks) * PIXELS_PER_CHUNK;
				int pixelZ = (dz + radiusChunks) * PIXELS_PER_CHUNK;
				graphics.setColor(color);
				graphics.fillRect(pixelX, pixelZ, PIXELS_PER_CHUNK, PIXELS_PER_CHUNK);
			}
		}

		graphics.dispose();
		writeImage(image, outputPath, trace, legendText());
		return outputPath;
	}

	public static Path renderOwnerMapFromCells(
			Map<Long, ChunkMapCell> cells,
			ChunkPos center,
			int radiusChunks,
			Path outputPath,
			Map<UUID, String> ownerNames,
			MapExportTrace trace
	) {
		if (trace != null) {
			trace.step("renderOwnerMapFromCells begin, pixels=%dx%d",
					(radiusChunks * 2 + 1) * PIXELS_PER_CHUNK,
					(radiusChunks * 2 + 1) * PIXELS_PER_CHUNK);
		}

		Map<UUID, Color> ownerColors = colorOwners(cells);
		int size = radiusChunks * 2 + 1;
		BufferedImage image = new BufferedImage(size * PIXELS_PER_CHUNK, size * PIXELS_PER_CHUNK, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();

		for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
			for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
				long key = ChunkPos.asLong(center.x + dx, center.z + dz);
				ChunkMapCell cell = cells.getOrDefault(key, ChunkMapCell.natural());
				Color color = colorForOwnerCell(cell, ownerColors);
				int pixelX = (dx + radiusChunks) * PIXELS_PER_CHUNK;
				int pixelZ = (dz + radiusChunks) * PIXELS_PER_CHUNK;
				graphics.setColor(color);
				graphics.fillRect(pixelX, pixelZ, PIXELS_PER_CHUNK, PIXELS_PER_CHUNK);
			}
		}

		graphics.dispose();
		writeImage(image, outputPath, trace, ownerLegendText(ownerColors, ownerNames));
		return outputPath;
	}

	public static Path renderFull(ServerLevel level, Path outputPath) {
		Map<Long, ChunkTerritoryData> chunks = WorldRegionData.get(level).getAllChunks();
		if (chunks.isEmpty()) {
			BufferedImage empty = new BufferedImage(PIXELS_PER_CHUNK, PIXELS_PER_CHUNK, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = empty.createGraphics();
			graphics.setColor(colorForState(ChunkState.NATURAL));
			graphics.fillRect(0, 0, PIXELS_PER_CHUNK, PIXELS_PER_CHUNK);
			graphics.dispose();
			writeImage(empty, outputPath, null, legendText());
			return outputPath;
		}

		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (long key : chunks.keySet()) {
			ChunkPos pos = new ChunkPos(key);
			minX = Math.min(minX, pos.x);
			maxX = Math.max(maxX, pos.x);
			minZ = Math.min(minZ, pos.z);
			maxZ = Math.max(maxZ, pos.z);
		}

		int width = (maxX - minX + 1) * PIXELS_PER_CHUNK;
		int height = (maxZ - minZ + 1) * PIXELS_PER_CHUNK;
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(colorForState(ChunkState.NATURAL));
		graphics.fillRect(0, 0, width, height);

		for (Map.Entry<Long, ChunkTerritoryData> entry : chunks.entrySet()) {
			ChunkPos pos = new ChunkPos(entry.getKey());
			Color color = colorForState(entry.getValue().getState());
			int pixelX = (pos.x - minX) * PIXELS_PER_CHUNK;
			int pixelZ = (pos.z - minZ) * PIXELS_PER_CHUNK;
			graphics.setColor(color);
			graphics.fillRect(pixelX, pixelZ, PIXELS_PER_CHUNK, PIXELS_PER_CHUNK);
		}

		graphics.dispose();
		writeImage(image, outputPath, null, legendText());
		return outputPath;
	}

	public static Color colorForState(ChunkState state) {
		return switch (state) {
			case NATURAL -> new Color(34, 139, 34);
			case OCCUPIED -> new Color(30, 144, 255);
			case BORDER -> new Color(255, 140, 0);
			case HOSTILE_BORDER -> new Color(220, 20, 60);
			case SAFE -> new Color(169, 169, 169);
			case DEATH -> new Color(128, 0, 128);
		};
	}

	public static String legendText() {
		return """
				Chunk State Colors:
				  NATURAL (1)        - Green   #228B22
				  OCCUPIED (2)       - Blue    #1E90FF
				  BORDER (3)         - Orange  #FF8C00
				  HOSTILE_BORDER (4) - Red     #DC143C
				  SAFE (5)           - Gray    #A9A9A9
				  DEATH (6)          - Purple  #800080
				""";
	}

	public static String ownerLegendText(Map<UUID, Color> ownerColors, Map<UUID, String> ownerNames) {
		StringBuilder builder = new StringBuilder();
		builder.append("Territory Division Colors (adjacent owners use different colors):\n");
		List<Map.Entry<UUID, Color>> entries = new ArrayList<>(ownerColors.entrySet());
		entries.sort(Comparator.comparing(entry -> ownerNames.getOrDefault(entry.getKey(), entry.getKey().toString())));
		for (Map.Entry<UUID, Color> entry : entries) {
			String name = ownerNames.getOrDefault(entry.getKey(), entry.getKey().toString());
			builder.append("  ").append(name).append(" - ").append(toHex(entry.getValue())).append('\n');
		}
		builder.append("  Unclaimed - White   ").append(toHex(OWNER_UNCLAIMED)).append('\n');
		builder.append("  DEATH     - Black   ").append(toHex(OWNER_DEATH)).append('\n');
		return builder.toString();
	}

	private static Map<UUID, Color> colorOwners(Map<Long, ChunkMapCell> cells) {
		Map<UUID, Set<UUID>> adjacency = new HashMap<>();
		for (ChunkMapCell cell : cells.values()) {
			UUID owner = territoryOwner(cell);
			if (owner != null) {
				adjacency.computeIfAbsent(owner, id -> new HashSet<>());
			}
		}

		for (Map.Entry<Long, ChunkMapCell> entry : cells.entrySet()) {
			UUID owner = territoryOwner(entry.getValue());
			if (owner == null) {
				continue;
			}
			ChunkPos pos = new ChunkPos(entry.getKey());
			for (int i = 0; i < CARDINAL_DX.length; i++) {
				long neighborKey = ChunkPos.asLong(pos.x + CARDINAL_DX[i], pos.z + CARDINAL_DZ[i]);
				ChunkMapCell neighbor = cells.get(neighborKey);
				UUID neighborOwner = territoryOwner(neighbor);
				if (neighborOwner != null && !owner.equals(neighborOwner)) {
					adjacency.get(owner).add(neighborOwner);
					adjacency.computeIfAbsent(neighborOwner, id -> new HashSet<>()).add(owner);
				}
			}
		}

		Map<UUID, Integer> colorIndex = greedyColor(adjacency);
		Map<UUID, Color> colors = new HashMap<>();
		colorIndex.forEach((owner, index) -> colors.put(owner, colorAt(index)));
		return colors;
	}

	private static Map<UUID, Integer> greedyColor(Map<UUID, Set<UUID>> adjacency) {
		List<UUID> nodes = new ArrayList<>(adjacency.keySet());
		nodes.sort((a, b) -> {
			int degree = Integer.compare(adjacency.get(b).size(), adjacency.get(a).size());
			if (degree != 0) {
				return degree;
			}
			return a.compareTo(b);
		});

		Map<UUID, Integer> colors = new HashMap<>();
		for (UUID node : nodes) {
			BitSet used = new BitSet();
			for (UUID neighbor : adjacency.get(node)) {
				Integer color = colors.get(neighbor);
				if (color != null) {
					used.set(color);
				}
			}
			colors.put(node, used.nextClearBit(0));
		}
		return colors;
	}

	private static Color colorAt(int index) {
		if (index < OWNER_PALETTE.length) {
			return OWNER_PALETTE[index];
		}
		float hue = ((index - OWNER_PALETTE.length) * 0.618033988749895f) % 1.0f;
		return Color.getHSBColor(hue, 0.78f, 0.88f);
	}

	private static Color colorForOwnerCell(ChunkMapCell cell, Map<UUID, Color> ownerColors) {
		if (cell.state() == ChunkState.DEATH) {
			return OWNER_DEATH;
		}
		UUID owner = territoryOwner(cell);
		if (owner == null) {
			return OWNER_UNCLAIMED;
		}
		return ownerColors.getOrDefault(owner, OWNER_UNCLAIMED);
	}

	private static UUID territoryOwner(ChunkMapCell cell) {
		if (cell == null || !cell.state().isOccupiedFamily()) {
			return null;
		}
		return cell.occupyingOrg();
	}

	private static String toHex(Color color) {
		return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
	}

	private static void writeImage(BufferedImage image, Path outputPath, MapExportTrace trace, String legend) {
		if (trace != null) {
			trace.step("writeImage begin: %s", outputPath);
		}
		try {
			if (outputPath.getParent() != null) {
				Files.createDirectories(outputPath.getParent());
			}
			ImageIO.write(image, "png", outputPath.toFile());
			Path legendPath = outputPath.resolveSibling(outputPath.getFileName().toString().replace(".png", "-legend.txt"));
			Files.writeString(legendPath, legend);
			PlayerBlockStatus.LOGGER.info("Chunk debug map written to {} (legend: {})",
					outputPath.toAbsolutePath(), legendPath.toAbsolutePath());
			if (trace != null) {
				trace.step("writeImage done: %s", outputPath.toAbsolutePath());
			}
		} catch (IOException exception) {
			throw new RuntimeException("Failed to write chunk debug map", exception);
		}
	}
}
