package luowei.player_block_status.lib.api;

/**
 * 实体轮询列表变更钩子。消费模组若持有指向列表单元的下标指针，应在此回调中更新。
 */
@FunctionalInterface
public interface EntityPollListListener {
	void onEntityPollListChanged(EntityPollListChange change);
}
