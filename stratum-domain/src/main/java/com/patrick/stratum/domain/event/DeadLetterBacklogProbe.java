package com.patrick.stratum.domain.event;

import java.util.OptionalLong;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;

/**
 * 只查询固定订阅组的Broker可见死信数，不消费或更改单据。
 * <p>示例：采集用例调用{@code probe.ready(Group.ERP_AUDIT)}。</p>
 */
public interface DeadLetterBacklogProbe {
    /**
     * 查询一次可见数；缺失或不可用返回空值，未确认及延迟消息不计入。
     * @param group 固定订阅组
     * @return 非负可见数或不可用
     * @throws NullPointerException 订阅组为空
     * @apiNote 示例：{@code probe.ready(Group.CACHE_INVALIDATION)}。
     */
    OptionalLong ready(Group group);
}
