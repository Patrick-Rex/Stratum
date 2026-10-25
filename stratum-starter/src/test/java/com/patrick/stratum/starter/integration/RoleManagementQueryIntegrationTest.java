package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.auth.AdministrationQueryAccess;
import com.patrick.stratum.query.auth.RoleManagementQuery;
import com.patrick.stratum.query.auth.RoleManagementQueryHandler;
import com.patrick.stratum.query.auth.RoleView;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实管理员角色查询验证稳定分页、字面文本、当前版本及授权边界。 */
class RoleManagementQueryIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @Autowired private QueryGateway gateway;
    @Autowired private UnitOfWork work;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private AdministrationQueryAccess access;
    @Autowired private JdbcTemplate jdbc;
    private final List<UUID> owned = new ArrayList<>();
    private final String prefix = "role-list-" + UUID.randomUUID();

    /** 仅删除本测试创建的空权限角色，不删除初始化管理员。 */
    @AfterEach
    void clean() { for (UUID id : owned) jdbc.update("DELETE FROM auth_role WHERE id=?", id.toString()); }

    /** 同名称列表补充ID稳定排序，名称和版本变化即时读取，保留管理员角色可查询。 */
    @Test
    void pagesRolesAndReadsUpdatedAndReservedDetails() {
        UUID first = role("-a", "同名"); UUID second = role("-b", "同名");
        var filters = List.of(filter("code", QuerySpec.Operator.CONTAINS, prefix, null));
        var page1 = read(1, 1, List.of("name,asc"), filters); var page2 = read(2, 1, List.of("name,asc"), filters);
        assertEquals(2, page1.getTotal());
        assertEquals(List.of(first, second).stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList(),
                List.of(page1.getItems().getFirst().id(), page2.getItems().getFirst().id()));
        work.execute(() -> { var role = roles.findById(first.toString()).orElseThrow(); role.rename("已更新"); roles.save(role); return null; });
        var detail = read(1, 1, List.of(), List.of(filter("id", QuerySpec.Operator.EQ, first, null))).getItems().getFirst();
        assertEquals("已更新", detail.name()); assertEquals(1, detail.version()); assertEquals(prefix + "-a", detail.code());
        assertEquals(List.of(detail), read(1, 20, List.of("version,desc"), List.of(filter("code", QuerySpec.Operator.CONTAINS, prefix, null),
                filter("version", QuerySpec.Operator.BETWEEN, 1L, 2L))).getItems());
        assertEquals(1, read(1, 1, List.of(), List.of(filter("code", QuerySpec.Operator.EQ, AuthorizationRole.ADMINISTRATOR_CODE, null))).getTotal());
        assertEquals(0, read(1, 1, List.of(), List.of(filter("id", QuerySpec.Operator.EQ, UUID.randomUUID(), null))).getTotal());
    }

    /** 模糊搜索将百分号、下划线和感叹号按字面转义，SQL文本无法扩大结果。 */
    @Test
    void bindsLiteralTextAndRejectsUnapprovedFields() {
        String name = prefix + "_%!"; UUID special = role("-special", name); role("-other", prefix + "-other");
        assertEquals(List.of(special), read(1, 20, List.of("code,desc"), List.of(filter("name", QuerySpec.Operator.CONTAINS, "_%!", null)))
                .getItems().stream().map(RoleManagementQuery.Row::id).toList());
        assertEquals(1, read(1, 20, List.of(), List.of(filter("name", QuerySpec.Operator.EQ, name, null))).getTotal());
        assertEquals(0, read(1, 20, List.of(), List.of(filter("name", QuerySpec.Operator.CONTAINS, "' OR 1=1 --", null))).getTotal());
        assertThrows(IllegalArgumentException.class, () -> RoleManagementQuery.create(1, 20, List.of("permissions,asc"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> RoleManagementQuery.create(1, 20, List.of(), List.of(filter("id", QuerySpec.Operator.EQ, "invalid", null))));
        assertThrows(IllegalArgumentException.class, () -> RoleManagementQuery.create(1, 20, List.of(), List.of(filter("version", QuerySpec.Operator.BETWEEN, 3L, 1L))));
        assertEquals(200, RoleManagementQuery.create(1, 1000, List.of(), List.of()).spec().getPageRequest().getPageSize());
    }

    /** 匿名或自报管理权限不能读取角色，资格移除即拒绝，写事务不允许读侧调用。 */
    @Test
    void enforcesCurrentAdministratorAndTransactionBoundary() {
        var query = RoleManagementQuery.create(1, 20, List.of(), List.of());
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, () -> gateway.execute(query, QueryContext.anonymous("role-list"))).getErrorCode());
        var forged = new QueryContext.Snapshot("role-list", Optional.of(UUID.randomUUID().toString()), Set.of("auth.role.manage"));
        assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, () -> gateway.execute(query, forged)).getErrorCode());
        String administrator = jdbc.queryForObject("SELECT id FROM auth_role WHERE code=?", String.class, AuthorizationRole.ADMINISTRATOR_CODE);
        jdbc.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=?", subjectId(), administrator);
        try { assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, () -> gateway.execute(query, context())).getErrorCode()); }
        finally { jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", subjectId(), administrator); }
        assertThrows(IllegalStateException.class, () -> work.execute(() -> gateway.execute(query, context())));
    }

    /** 存储读取失败不返回空成功或敏感原因链。 */
    @Test
    void sanitizesStorageFailure() {
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(RoleView.class)).thenThrow(new EasyQueryException("sensitive-role-list"));
        var handler = new RoleManagementQueryHandler(database, access);
        var failure = assertThrows(StratumException.class, () -> handler.handle(RoleManagementQuery.create(1, 20, List.of(), List.of()), context()));
        assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode()); assertNull(failure.getCause());
        assertFalse(failure.toString().contains("sensitive-role-list"));
    }

    /**
     * 保存本测试空权限角色。
     * @param suffix 唯一编码后缀
     * @param name 名称
     * @return 领域生成UUID
     */
    private UUID role(String suffix, String name) {
        var role = AuthorizationRole.create(prefix + suffix, name); UUID id = UUID.fromString(role.getId()); owned.add(id);
        work.execute(() -> { roles.save(role); return null; }); return id;
    }

    /**
     * 构造真实HTTP登录管理员的服务端查询上下文。
     * @return 服务器身份上下文
     */
    private QueryContext context() { return new QueryContext.Snapshot("role-list", Optional.of(subjectId()), Set.of()); }

    /**
     * 通过网关读取角色页。
     * @param page 页码
     * @param size 大小
     * @param sorts 排序
     * @param filters 条件
     * @return 只含角色元数据的结果
     */
    private QueryResult<RoleManagementQuery.Row> read(int page, int size, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        return gateway.execute(RoleManagementQuery.create(page, size, sorts, filters), context());
    }

    /**
     * 构造测试用白名单输入。
     * @param name 字段
     * @param operator 操作
     * @param value 值或下界
     * @param upper 上界
     * @return 输入条件
     */
    private static QuerySpec.FilterInput filter(String name, QuerySpec.Operator operator, Object value, Object upper) {
        return new QuerySpec.FilterInput(name, operator, value, upper);
    }
}
