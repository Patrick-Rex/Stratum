package com.patrick.stratum.domain.event;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;

import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;

/**
 * 固定订阅组的共享死信可见数快照；新采样使旧结果失效，缺失与故障不表示零。
 * <p>示例：开始采样后按组提交Broker可见数，查询者只读取当前未过期结果。</p>
 */
public interface DeadLetterBacklogStore {
    /**
     * 建立唯一采样代号并使所有旧采样不可见。
     * @return 新采样代号
     * @throws RuntimeException 共享存储不可用
     * @apiNote 示例：{@code UUID sample = store.begin()}。
     */
    UUID begin();

    /**
     * 仅当采样仍为当前代号时写入单组结果；空值表示该组不可用。
     * @param sample 本轮采样代号
     * @param group 固定订阅组
     * @param ready 非负可见数或不可用
     * @param ttl 有界有效期
     * @return 当前采样写入成功为true；已被新采样替代为false
     * @throws RuntimeException 共享存储不可用或输入不合法
     * @apiNote 示例：{@code store.record(sample, Group.ERP_AUDIT, OptionalLong.of(0), Duration.ofMinutes(2))}。
     */
    boolean record(UUID sample, Group group, OptionalLong ready, Duration ttl);

    /**
     * 读取当前采样的未过期可见数，缺失、不可用或存储故障返回空值。
     * @param group 固定订阅组
     * @return 非负可见数或不可用
     * @apiNote 示例：{@code store.read(Group.ERP_AUDIT)}。
     */
    OptionalLong read(Group group);
}
