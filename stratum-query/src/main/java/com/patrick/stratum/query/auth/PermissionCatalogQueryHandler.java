package com.patrick.stratum.query.auth;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 从有限的后端注册目录投影管理读模型；仍经实时管理员查询检查及QueryGateway统一观测。 */
@Component
@RequiredArgsConstructor
public class PermissionCatalogQueryHandler implements QueryHandler<PermissionCatalogQuery, PermissionCatalogQuery.Row> {
    private static final List<PermissionCatalogQuery.Row> CATALOG = Arrays.stream(PermissionCatalog.values())
            .map(entry -> new PermissionCatalogQuery.Row(entry.permission().code(), entry.permission().type(), entry.displayName())).toList();
    private final AdministrationQueryAccess access;

    /** {@inheritDoc} */
    @Override public String queryCode() { return "auth.permission.catalog"; }

    /** {@inheritDoc} */
    @Override public Class<PermissionCatalogQuery> queryType() { return PermissionCatalogQuery.class; }

    /** {@inheritDoc} */
    @Override public QueryResult<PermissionCatalogQuery.Row> handle(PermissionCatalogQuery query, QueryContext context) {
        access.require(context);
        var filtered = CATALOG.stream().filter(row -> query.spec().getFilters().stream().allMatch(filter -> matches(row, filter))).toList();
        Comparator<PermissionCatalogQuery.Row> comparator = (left, right) -> 0;
        for (var sort : query.spec().getSorts()) {
            Comparator<PermissionCatalogQuery.Row> next = Comparator.comparing(row -> value(row, sort.field()));
            comparator = comparator.thenComparing(sort.direction() == SortDirection.ASC ? next : next.reversed());
        }
        comparator = comparator.thenComparing(row -> row.type().name()).thenComparing(PermissionCatalogQuery.Row::code);
        var page = query.spec().getPageRequest(); long offset = (long) (page.getPage() - 1) * page.getPageSize();
        var rows = filtered.stream().sorted(comparator).skip(offset).limit(page.getPageSize()).toList();
        return new QueryResult<>(page.getPage(), page.getPageSize(), filtered.size(), rows);
    }

    /**
     * 执行白名单精确/字面包含条件，不将文本解释为模式或SQL。
     * @param row 目录条目
     * @param filter 已验证条件
     * @return 是否匹配
     */
    private static boolean matches(PermissionCatalogQuery.Row row, QuerySpec.Filter<PermissionCatalogQuery.Field> filter) {
        String text = value(row, filter.field()); String expected = (String) filter.value();
        return filter.operator() == QuerySpec.Operator.EQ ? text.equals(expected) : text.contains(expected);
    }

    /**
     * 提取固定白名单字段，不反射读取任意属性。
     * @param row 目录条目
     * @param field 白名单字段
     * @return 文本值
     */
    private static String value(PermissionCatalogQuery.Row row, PermissionCatalogQuery.Field field) {
        return switch (field) { case CODE -> row.code(); case TYPE -> row.type().name(); case NAME -> row.name(); };
    }
}
