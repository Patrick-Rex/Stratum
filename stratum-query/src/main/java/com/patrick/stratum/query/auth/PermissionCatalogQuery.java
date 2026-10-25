package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 后端已登记的可授予权限目录查询，使用与角色配置相同的权威目录。 */
public final class PermissionCatalogQuery implements Query<PermissionCatalogQuery.Row> {
    /** 目录公开字段白名单。 */
    public enum Field { CODE, TYPE, NAME }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of(
            "code", new QuerySpec.Field<>(Field.CODE, String.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.CONTAINS), true),
            "type", new QuerySpec.Field<>(Field.TYPE, String.class, Set.of(QuerySpec.Operator.EQ), true),
            "name", new QuerySpec.Field<>(Field.NAME, String.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.CONTAINS), true));
    private final QuerySpec<Field> spec;

    /**
     * 保存不可变查询规范。
     * @param spec 已验证白名单
     */
    private PermissionCatalogQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 构造有界目录页，权限类型严格为API或FEAT。
     * @param page 页码
     * @param pageSize 每页最多200项
     * @param sorts 白名单排序
     * @param filters 白名单条件
     * @return 目录查询
     * @apiNote 示例：{@code PermissionCatalogQuery.create(1, 20, List.of("code,asc"), List.of())}。
     */
    public static PermissionCatalogQuery create(int page, int pageSize, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        var spec = QuerySpec.create(page, pageSize, sorts, filters, FIELDS);
        for (var filter : spec.getFilters()) if (filter.field() == Field.TYPE) Permission.Type.valueOf((String) filter.value());
        return new PermissionCatalogQuery(spec);
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }

    /**
     * 可授予权限的精确标识及展示名称，不代表当前账号拥有该权限。
     * @param code 后端编码
     * @param type API或FEAT
     * @param name 中文名称
     */
    public record Row(String code, Permission.Type type, String name) { }
}
