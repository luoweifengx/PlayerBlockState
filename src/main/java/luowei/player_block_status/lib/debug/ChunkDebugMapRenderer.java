package luowei.player_block_status.lib.debug;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import luowei.player_block_status.PlayerBlockStatus;
import luowei.player_block_status.lib.api.PlayerBlockStatusLib;
import luowei.player_block_status.lib.chunk.ChunkState;
import luowei.player_block_status.lib.chunk.ChunkTerritoryData;

/**
 * 将区块状态渲染为 PNG 调试地图，按状态着色。
 */
public final class ChunkDebugMapRenderer {
	private static final int PIXELS_PER_CHUNK = 4;

	private ChunkDebugMapRenderer() {
	}

	public static Path render(ServerLevel level, ChunkPos center, int radiusChunks, Path outputPath) {
		int size = radiusChunks * 2 + 1;
		BufferedImage image = new BufferedImage(size * PIXELS_PER_CHUNK, size * PIXELS_PER_CHUNK, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();

		for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
			for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
				ChunkPos chunkPos = new ChunkPos(center.x + dx, center.z + dz);
				ChunkState state = PlayerBlockStatusLib.queryChunkState(level, chunkPos).orElse(ChunkState.NATURAL);
				Color color = colorForState(state);
				int pixelX = (dx + radiusChunks) * PIXELS_PER_CHUNK;
				int pixelZ = (dz + radiusChunks) * PIXELS_PER_CHUNK;
				graphics.setColor(color);
				graphics.fillRect(pixelX, pixelZ, PIXELS_PER_CHUNK, PIXELS_PER_CHUNK);
			}
		}

		graphics.dispose();
		writeImage(image, outputPath);
		return outputPath;
	}

	public static Path renderFull(ServerLevel level, Path outputPath) {
		Map<Long, ChunkTerritoryData> chunks = PlayerBlockStatusLib.queryAllChunks(level);
		if (chunks.isEmpty()) {
			BufferedImage empty = new BufferedImage(PIXELS_PER_CHUNK, PIXELS_PER_CHUNK, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = empty.createGraphics();
			graphics.setColor(colorForState(ChunkState.NATURAL));
			graphics.fillRect(0, 0, PIXELS_PER_CHUNK, PIXELS_PER_CHUNK);
			graphics.dispose();
			writeImage(empty, outputPath);
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
		writeImage(image, outputPath);
		return outputPath;
	}

	public static Color colorForState(ChunkState state) {
		return switch (state) {
			case NATURAL -> new Color(34, 139, 34);
			case OCCUPIED -> new Color(30, 144, 255);
			case BORDER -> new Color(0, 191, 255);
			case HOSTILE_BORDER -> new Color(255, 140, 0);
			case SAFE -> new Color(169, 169, 169);
			case DEATH -> new Color(178, 34, 34);
		};
	}

	public static String legendText() {
		return """
				Chunk State Colors:
				  NATURAL (1)        - Green   #228B22
				  OCCUPIED (2)       - Blue    #1E90FF
				  BORDER (3)         - Cyan    #00BFFF
				  HOSTILE_BORDER (4) - Orange  #FF8C00
				  SAFE (5)           - Gray    #A9A9A9
				  DEATH (6)          - Red     #B22222
				""";
	}

	private static void writeImage(BufferedImage image, Path outputPath) {
		try {
			if (outputPath.getParent() != null) {
				Files.createDirectories(outputPath.getParent());
			}
			ImageIO.write(image, "png", outputPath.toFile());
			Path legendPath = outputPath.resolveSibling(outputPath.getFileName().toString().replace(".png", "-legend.txt"));
			Files.writeString(legendPath, legendText());
			PlayerBlockStatus.LOGGER.info("Chunk debug map written to {} (legend: {})",
					outputPath.toAbsolutePath(), legendPath.toAbsolutePath());
		} catch (IOException exception) {
			throw new RuntimeException("Failed to write chunk debug map", exception);
		}
	}
}
