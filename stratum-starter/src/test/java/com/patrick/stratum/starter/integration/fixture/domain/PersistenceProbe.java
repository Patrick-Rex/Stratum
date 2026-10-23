package com.patrick.stratum.starter.integration.fixture.domain;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.domain.model.AggregateRoot;

/** 聚合根测试夹具：演示框架无关模型与持久化映射分离，不属于 ERP 业务模型。 */
public final class PersistenceProbe extends AggregateRoot<String> {
    private String payload;

    /**
     * 创建或重建测试聚合，版本为空表示尚未持久化。
     * @param id 聚合标识
     * @param payload 测试数据
     * @param version 数据库版本，新建时为空
     * @apiNote 示例：new PersistenceProbe(UUID.randomUUID().toString(), "初值", null)。
     */
    public PersistenceProbe(String id, String payload, Long version) {
        super(Objects.requireNonNull(id, "id"));
        this.payload = Objects.requireNonNull(payload, "payload");
        setVersion(version);
    }

    /**
     * 由领域生成随机业务标识。
     * @param payload 初始数据
     * @return 尚未持久化的聚合
     * @apiNote 示例：PersistenceProbe.create("初值")。
     */
    public static PersistenceProbe create(String payload) {
        return new PersistenceProbe(UUID.randomUUID().toString(), payload, null);
    }

    /**
     * 读取测试数据。
     * @return 当前数据
     * @apiNote 示例：probe.payload()。
     */
    public String payload() { return payload; }

    /**
     * 修改测试数据。
     * @param payload 新数据
     * @apiNote 示例：probe.changePayload("新值")。
     */
    public void changePayload(String payload) { this.payload = Objects.requireNonNull(payload, "payload"); }

    /**
     * 回填成功执行 SQL 后的版本；事务回滚后调用方必须丢弃本次聚合实例。
     * @param version 数据库新版本
     * @apiNote 仅持久化映射调用，例如 probe.markPersisted(1L)。
     */
    public void markPersisted(long version) { setVersion(version); }
}
