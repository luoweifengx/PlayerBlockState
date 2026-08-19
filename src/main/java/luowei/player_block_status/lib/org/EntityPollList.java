package luowei.player_block_status.lib.org;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import luowei.player_block_status.lib.api.EntityPollListChange;
import luowei.player_block_status.lib.api.EntityPollListChange.Kind;
import luowei.player_block_status.lib.api.EntityPollListChange.Mutation;
import luowei.player_block_status.lib.api.PlayerBlockStatusLib;

/**
 * 服务器级实体轮询列表：未入组织的玩家与正式组织共用同一队列（玩家视为独立组织）。
 * <p>
 * 变动规则：
 * <ul>
 *   <li>列表内玩家成立组织 → 删除这些玩家，组织追加到队尾</li>
 *   <li>组织成员离开 → 组织位置不变，离开的玩家追加到队尾；组织解散则同时移除组织</li>
 *   <li>玩家加入已有组织 → 删除该玩家条目，组织位置不变</li>
 *   <li>新增组织 → 追加到队尾</li>
 *   <li>组织合并 → 移除被合并组织，目标组织位置不变</li>
 * </ul>
 */
public final class EntityPollList {
	private final ArrayList<UUID> order = new ArrayList<>();
	private final Map<UUID, Integer> indexOf = new HashMap<>();
	private final List<Mutation> pending = new ArrayList<>();
	private final Runnable onDirty;

	public EntityPollList(Runnable onDirty) {
		this.onDirty = onDirty == null ? () -> {
		} : onDirty;
	}

	public void load(List<UUID> savedOrder) {
		order.clear();
		indexOf.clear();
		pending.clear();
		if (savedOrder == null) {
			return;
		}
		for (UUID id : savedOrder) {
			if (id == null || indexOf.containsKey(id)) {
				continue;
			}
			indexOf.put(id, order.size());
			order.add(id);
		}
	}

	public List<UUID> snapshot() {
		return List.copyOf(order);
	}

	public List<UUID> toPersist() {
		return List.copyOf(order);
	}

	public int size() {
		return order.size();
	}

	public UUID get(int index) {
		return order.get(index);
	}

	public int indexOf(UUID id) {
		Integer index = indexOf.get(id);
		return index == null ? -1 : index;
	}

	public boolean contains(UUID id) {
		return indexOf.containsKey(id);
	}

	public List<UUID> asUnmodifiableList() {
		return Collections.unmodifiableList(order);
	}

	/** 若不在列表中则追加到队尾（用于玩家进服、补齐遗漏组织等）。 */
	public void ensurePresent(UUID id) {
		if (id == null || indexOf.containsKey(id)) {
			return;
		}
		appendInternal(id);
		flush();
	}

	/** 批量 ensure，只触发一次钩子。 */
	public void ensureAllPresent(Collection<UUID> ids) {
		if (ids == null || ids.isEmpty()) {
			return;
		}
		boolean changed = false;
		for (UUID id : ids) {
			if (id == null || indexOf.containsKey(id)) {
				continue;
			}
			appendInternal(id);
			changed = true;
		}
		if (changed) {
			flush();
		}
	}

	/**
	 * 玩家成立组织：移除所有加入成员在列表中的位置，将组织追加到队尾。
	 */
	public void onOrganizationCreated(UUID orgId, Collection<UUID> memberIds) {
		if (orgId == null) {
			return;
		}
		if (memberIds != null) {
			for (UUID memberId : memberIds) {
				removeInternal(memberId);
			}
		}
		removeInternal(orgId);
		appendInternal(orgId);
		flush();
	}

	/**
	 * 玩家加入已有组织：仅移除该玩家；组织位置不变。
	 */
	public void onPlayerJoinedOrganization(UUID playerId) {
		removeInternal(playerId);
		flush();
	}

	/**
	 * 玩家离开组织：组织位置不变；若组织已解散则移除组织；离开玩家追加到队尾。
	 */
	public void onPlayerLeftOrganization(UUID playerId, UUID orgId, boolean organizationDissolved) {
		if (organizationDissolved && orgId != null) {
			removeInternal(orgId);
		}
		removeInternal(playerId);
		appendInternal(playerId);
		flush();
	}

	/**
	 * 组织合并：移除被合并组织；目标组织位置不变。
	 */
	public void onOrganizationsMerged(UUID fromOrgId, UUID toOrgId) {
		removeInternal(fromOrgId);
		if (toOrgId != null && !indexOf.containsKey(toOrgId)) {
			appendInternal(toOrgId);
		}
		flush();
	}

	/**
	 * 启动时对齐：补齐缺失组织到队尾；剔除已入组织却仍留在列表中的玩家。
	 * 整次对齐只触发一次钩子。
	 */
	public void reconcile(java.util.Collection<UUID> organizationIds, java.util.Collection<UUID> playersInOrganizations) {
		if (organizationIds != null) {
			for (UUID orgId : organizationIds) {
				if (orgId != null && !indexOf.containsKey(orgId)) {
					appendInternal(orgId);
				}
			}
		}
		if (playersInOrganizations != null) {
			for (UUID playerId : playersInOrganizations) {
				removeInternal(playerId);
			}
		}
		flush();
	}

	private void removeInternal(UUID id) {
		if (id == null) {
			return;
		}
		Integer index = indexOf.get(id);
		if (index == null) {
			return;
		}
		order.remove((int) index);
		indexOf.remove(id);
		for (int i = index; i < order.size(); i++) {
			indexOf.put(order.get(i), i);
		}
		pending.add(new Mutation(Kind.REMOVED, id, index));
	}

	private void appendInternal(UUID id) {
		if (id == null || indexOf.containsKey(id)) {
			return;
		}
		int index = order.size();
		order.add(id);
		indexOf.put(id, index);
		pending.add(new Mutation(Kind.APPENDED, id, index));
	}

	private void flush() {
		if (pending.isEmpty()) {
			return;
		}
		List<Mutation> mutations = List.copyOf(pending);
		pending.clear();
		onDirty.run();
		PlayerBlockStatusLib.notifyEntityPollListListeners(new EntityPollListChange(order, mutations));
	}
}
