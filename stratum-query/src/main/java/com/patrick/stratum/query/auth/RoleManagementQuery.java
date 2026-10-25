package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 管理角色分页及ID详情查询，固定字段白名单，不隐式展开权限和成员关系。 */
public final class RoleManagementQuery implements Query<RoleManagementQuery.Row> {
    /** 只允许查询和排序角色元数据。 */
    public enum Field { ID, CODE, NAME, VERSION }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of(
            "id", new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), true),
            "code", new QuerySpec.Field<>(Field.CODE, String.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.CONTAINS), true),
            "name", new QuerySpec.Field<>(Field.NAME, String.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.CONTAINS), true),
            "version", new QuerySpec.Field<>(Field.VERSION, Long.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.BETWEEN), true));
    private final QuerySpec<Field> spec;

    /**
     * 保存不可变查询规范。
     * @param spec 已验证白名单规范
     */
    private RoleManagementQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 构造分页查询，ID精确条件亦用于读取角色详情，未知角色返回空页。
     * @param page 页码
     * @param pageSize 页大小，最大200
     * @param sorts 白名单排序
     * @param filters 白名单条件
     * @return 不包含任意SQL表达式的查询
     * @apiNote 示例：{@code RoleManagementQuery.create(1, 20, List.of("code,asc"), List.of())}。
     */
    public static RoleManagementQuery create(int page, int pageSize, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        return new RoleManagementQuery(QuerySpec.create(page, pageSize, sorts, filters, FIELDS));
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }

    /**
     * 角色管理不可变结果，版本与角色配置写用例共用。
     * @param id 角色UUID
     * @param code 固定角色编码
     * @param name 可修改角色名称
     * @param version 当前持久化版本
     */
    public record Row(UUID id, String code, String name, long version) { }
}
