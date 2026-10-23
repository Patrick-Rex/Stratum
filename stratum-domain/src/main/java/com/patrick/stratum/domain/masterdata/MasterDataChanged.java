package com.patrick.stratum.domain.masterdata;

import java.util.Objects;

import com.patrick.stratum.domain.event.DomainEvent;

/** 领域事件：主数据变更完成后的不可变快照，后续改名不会修改已产生的事件。 */
public final class MasterDataChanged extends DomainEvent {
    /** 已发生的主数据变更类型。 */
    public enum Change { CREATED, RENAMED, DELETED }

    private final Change change;
    private final MasterData.Kind kind;
    private final String code;
    private final String name;
    private final String baseUnit;
    private final long version;

    /**
     * 从持久化成功的聚合生成事件；删除事件使用比旧版本高1的终结版本。
     * @param aggregate 已保存或刚删除的聚合
     * @param change 已完成动作
     * @throws IllegalArgumentException 聚合尚未持久化
     * @apiNote 示例：new MasterDataChanged(material, Change.CREATED)。
     */
    public MasterDataChanged(MasterData aggregate, Change change) {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(change, "change");
        if (aggregate.getVersion() == null) throw new IllegalArgumentException("事件需要持久化版本");
        super(aggregate.getId());
        this.change = change;
        this.kind = aggregate.getKind();
        this.code = aggregate.getCode();
        this.name = aggregate.getName();
        this.baseUnit = aggregate.getBaseUnit();
        this.version = change == Change.DELETED ? Math.incrementExact(aggregate.getVersion()) : aggregate.getVersion();
    }

    /**
     * 获取变更动作。
     * @return 变更动作
     * @apiNote 示例：event.getChange()。
     */
    public Change getChange() { return change; }
    /**
     * 获取主数据类别。
     * @return 固定类别
     * @apiNote 示例：event.getKind()。
     */
    public MasterData.Kind getKind() { return kind; }
    /**
     * 获取编码快照。
     * @return 编码
     * @apiNote 示例：event.getCode()。
     */
    public String getCode() { return code; }
    /**
     * 获取变更时名称。
     * @return 名称快照
     * @apiNote 示例：event.getName()。
     */
    public String getName() { return name; }
    /**
     * 获取单位快照。
     * @return 基本单位，非物料为空
     * @apiNote 示例：event.getBaseUnit()。
     */
    public String getBaseUnit() { return baseUnit; }
    /**
     * 获取事件版本。
     * @return 已保存版本或删除终结版本
     * @apiNote 示例：event.getVersion()。
     */
    public long getVersion() { return version; }
}
