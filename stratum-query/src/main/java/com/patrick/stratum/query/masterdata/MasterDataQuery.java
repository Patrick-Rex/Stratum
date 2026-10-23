package com.patrick.stratum.query.masterdata;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;
import lombok.Getter;

/** 四类主数据的分页及ID详情查询，只允许服务器声明的字段与操作。 */
@Getter
public final class MasterDataQuery implements Query<MasterDataQuery.Row> {
    /** 主数据可查询字段，不允许客户端提供数据库列名。 */
    public enum Field { ID, CODE, NAME }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of(
            "id", new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), true),
            "code", new QuerySpec.Field<>(Field.CODE, String.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.CONTAINS), true),
            "name", new QuerySpec.Field<>(Field.NAME, String.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.CONTAINS), true));
    private final MasterData.Kind kind;
    private final QuerySpec<Field> spec;

    /**
     * 保存已经通过固定白名单解析的查询。
     * @param kind 主数据类别
     * @param spec 查询条件
     */
    private MasterDataQuery(MasterData.Kind kind, QuerySpec<Field> spec) {
        this.kind = Objects.requireNonNull(kind, "kind"); this.spec = spec;
    }

    /**
     * 创建有界分页请求；ID过滤可用于详情，类型不符、未知字段或操作会被拒绝。
     * @param kind 主数据类别
     * @param page 页码
     * @param pageSize 每页条数
     * @param sorts 排序表达
     * @param filters 过滤表达
     * @return 不可变请求
     * @apiNote 示例：MasterDataQuery.create(Kind.MATERIAL, 1, 20, List.of("code,asc"), List.of())。
     */
    public static MasterDataQuery create(MasterData.Kind kind, int page, int pageSize,
            List<String> sorts, List<QuerySpec.FilterInput> filters) {
        return new MasterDataQuery(kind, QuerySpec.create(page, pageSize, sorts, filters, FIELDS));
    }

    /** {@inheritDoc} */
    @Override
    public QuerySpec<Field> spec() { return spec; }

    /**
     * 不可变主数据读模型，不向调用方暴露ORM对象。
     * @param id 标识
     * @param kind 类别
     * @param code 编码
     * @param name 名称
     * @param baseUnit 物料基本单位，其他类别为空
     * @param version 当前版本
     */
    public record Row(UUID id, MasterData.Kind kind, String code, String name, String baseUnit, long version) { }
}
