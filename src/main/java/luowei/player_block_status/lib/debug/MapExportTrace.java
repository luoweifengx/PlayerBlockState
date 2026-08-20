package luowei.player_block_status.lib.debug;

import luowei.player_block_status.PlayerBlockStatus;

/**
 * /pbs map 导出过程分步日志，输出到服务端终端。
 */
public final class MapExportTrace {
	private final long startNanos = System.nanoTime();
	private final String label;

	public MapExportTrace(String label) {
		this.label = label;
		step("trace started");
	}

	public void step(String message) {
		PlayerBlockStatus.LOGGER.debug("[pbs map][{}] +{}ms {}",
				label,
				elapsedMillis(),
				message
		);
	}

	public void step(String message, Object... args) {
		step(String.format(message, args));
	}

	public long elapsedMillis() {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}
}
