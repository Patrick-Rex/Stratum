package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.auth.PermissionCatalogQuery;
import com.patrick.stratum.query.auth.RolePermissionsQuery;
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实管理员和查询网关验收可授予目录、管理员授权一致性及读侧边界。 */
class PermissionCatalogQueryIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @Autowired private QueryGateway gateway;
    @Autowired private UnitOfWork work;
    @Autowired private JdbcTemplate jdbc;

    /** 分页完整且无重叠，类型、名称和精确标识与领域目录及初始化管理员授权一致。 */
    @Test
    void pagesTheRegisteredCatalogAndMatchesAdministratorGrants() {
        var expected = Arrays.stream(PermissionCatalog.values()).map(entry -> new PermissionCatalogQuery.Row(
                entry.permission().code(), entry.permission().type(), entry.displayName())).collect(Collectors.toSet());
        var actual = new ArrayList<PermissionCatalogQuery.Row>();
        for (int page = 1; actual.size() < expected.size(); page++) {
            var result = gateway.execute(PermissionCatalogQuery.create(page, 7, List.of("type,desc", "name,asc"), List.of()), context());
            assertEquals(expected.size(), result.getTotal()); assertFalse(result.getItems().isEmpty()); actual.addAll(result.getItems());
        }
        assertEquals(expected.size(), actual.size()); assertEquals(expected, Set.copyOf(actual));
        UUID administrator = UUID.fromString(jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class));
        var granted = gateway.execute(RolePermissionsQuery.create(administrator), context()).getItems().getFirst().permissions();
        assertEquals(granted, actual.stream().map(row -> new Permission(row.code(), row.type())).collect(Collectors.toSet()));
        assertTrue(gateway.execute(PermissionCatalogQuery.create(Integer.MAX_VALUE, 200, List.of(), List.of()), context()).getItems().isEmpty());
    }

    /** 类型精确过滤、名称字面包含及白名单输入边界均沿用QuerySpec，通配文本不扩大结果。 */
    @Test
    void filtersExactTypesAndLiteralText() {
        var filters = List.of(new QuerySpec.FilterInput("type", QuerySpec.Operator.EQ, "API", null),
                new QuerySpec.FilterInput("name", QuerySpec.Operator.CONTAINS, "查询", null));
        var rows = gateway.execute(PermissionCatalogQuery.create(1, 200, List.of("code,asc"), filters), context()).getItems();
        assertFalse(rows.isEmpty()); assertTrue(rows.stream().allMatch(row -> row.type() == Permission.Type.API && row.name().contains("查询")));
        for (String literal : List.of("%", "_", "' OR 1=1 --")) {
            var query = PermissionCatalogQuery.create(1, 20, List.of(), List.of(new QuerySpec.FilterInput("code", QuerySpec.Operator.CONTAINS, literal, null)));
            assertEquals(0, gateway.execute(query, context()).getTotal());
        }
        assertThrows(IllegalArgumentException.class, () -> PermissionCatalogQuery.create(1, 20, List.of("unknown,asc"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> PermissionCatalogQuery.create(1, 20, List.of(), List.of(new QuerySpec.FilterInput("type", QuerySpec.Operator.EQ, "api", null))));
        assertThrows(IllegalArgumentException.class, () -> PermissionCatalogQuery.create(1, 20, List.of(), List.of(new QuerySpec.FilterInput("type", QuerySpec.Operator.CONTAINS, "API", null))));
    }

    /** 目录只向当前启用管理员提供，数据库中的账号停用后不能继续读静态条目，写事务亦拒绝。 */
    @Test
    void checksLiveAdministratorEvenForTheStaticCatalog() {
        var query = PermissionCatalogQuery.create(1, 20, List.of(), List.of());
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, () -> gateway.execute(query, QueryContext.anonymous("catalog-query"))).getErrorCode());
        var forged = new QueryContext.Snapshot("catalog-query", Optional.of(UUID.randomUUID().toString()), Set.of("auth.role.manage"));
        assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, () -> gateway.execute(query, forged)).getErrorCode());
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", subjectId());
        try { assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, () -> gateway.execute(query, context())).getErrorCode()); }
        finally { jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", subjectId()); }
        assertThrows(IllegalStateException.class, () -> work.execute(() -> gateway.execute(query, context())));
    }

    /**
     * 从真实HTTP登录夹具取得管理员主体，不从客户端绑定权限。
     * @return 服务器上下文
     */
    private QueryContext context() { return new QueryContext.Snapshot("catalog-query", Optional.of(subjectId()), Set.of()); }
}
