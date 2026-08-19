package luowei.player_block_status.lib.api;

import java.util.List;
import java.util.UUID;

/**
 * 实体轮询列表一次变更的结果。
 * <p>
 * {@link Mutation} 按实际应用顺序排列：监听方可据此平移自身保存的下标指针。
 * <ul>
 *   <li>{@link Kind#REMOVED}：{@code index} 为移除前下标；其后所有下标减 1</li>
 *   <li>{@link Kind#APPENDED}：{@code index} 为追加后下标（队尾）</li>
 * </ul>
 */
public final class EntityPollListChange {
	private final List<UUID> orderAfter;
	private final List<Mutation> mutations;

	public EntityPollListChange(List<UUID> orderAfter, List<Mutation> mutations) {
		this.orderAfter = List.copyOf(orderAfter);
		this.mutations = List.copyOf(mutations);
	}

	/** 变更完成后的完整顺序快照（只读）。 */
	public List<UUID> orderAfter() {
		return orderAfter;
	}

	/** 按应用顺序排列的原子变更。 */
	public List<Mutation> mutations() {
		return mutations;
	}

	public enum Kind {
		REMOVED,
		APPENDED
	}

	public record Mutation(Kind kind, UUID id, int index) {
	}
}
