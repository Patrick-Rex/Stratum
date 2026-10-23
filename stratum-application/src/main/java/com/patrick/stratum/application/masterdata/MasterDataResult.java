package com.patrick.stratum.application.masterdata;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.patrick.stratum.domain.masterdata.MasterData;
import lombok.Builder;
import lombok.Getter;

/** 主数据用例结果快照，固定构造契约支持幂等回执与版本缓存反序列化。 */
@Getter
public final class MasterDataResult {
    private final String id;
    private final MasterData.Kind kind;
    private final String code;
    private final String name;
    private final String baseUnit;
    private final long version;

    /**
     * 构造不可变结果，不携带请求traceId。
     * @param id 主数据UUID
     * @param kind 类别
     * @param code 编码
     * @param name 名称
     * @param baseUnit 基本单位，非物料为空
     * @param version 已提交的聚合版本
     * @apiNote 示例：MasterDataResult.builder().id(id).kind(kind).code(code).name(name).version(0).build()。
     */
    @Builder
    @JsonCreator
    public MasterDataResult(@JsonProperty("id") String id, @JsonProperty("kind") MasterData.Kind kind,
            @JsonProperty("code") String code, @JsonProperty("name") String name,
            @JsonProperty("baseUnit") String baseUnit, @JsonProperty("version") long version) {
        this.id = id; this.kind = kind; this.code = code; this.name = name; this.baseUnit = baseUnit; this.version = version;
    }

    /**
     * 从已持久化聚合构造独立快照。
     * @param aggregate 已保存聚合
     * @return 结果快照
     * @apiNote 示例：MasterDataResult.from(material)。
     */
    public static MasterDataResult from(MasterData aggregate) {
        return new MasterDataResult(aggregate.getId(), aggregate.getKind(), aggregate.getCode(),
                aggregate.getName(), aggregate.getBaseUnit(), aggregate.getVersion());
    }
}
