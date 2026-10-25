package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.auth.AccountManagementQuery;
import com.patrick.stratum.query.auth.AccountManagementQueryHandler;
import com.patrick.stratum.query.auth.AccountManagementView;
import com.patrick.stratum.query.auth.AdministrationQueryAccess;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实管理员身份查询账号列表，验证有界稳定分页、无凭据输出及授权失败关闭。 */
class AccountManagementQueryIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @Autowired private QueryGateway gateway;
    @Autowired private UnitOfWork work;
    @Autowired private LocalAccountRepository accounts;
    @Autowired private PasswordHasher passwords;
    @Autowired private AdministrationQueryAccess access;
    @Autowired private JdbcTemplate jdbc;
    private final List<UUID> owned = new ArrayList<>();
    private final String prefix = "list-" + UUID.randomUUID();

    /** 仅清理本用例普通账号，管理员与会话由父夹具清理。 */
    @AfterEach
    void clean() { for (UUID id : owned) jdbc.update("DELETE FROM auth_user WHERE id=?", id.toString()); }

    /** 同状态排序追加ID保证分页稳定，ID/版本区间读取反映当前启用状态与版本。 */
    @Test
    void pagesDeterministicallyAndReadsCurrentDetails() {
        UUID first = account("-a"); UUID second = account("-b");
        var filters = List.of(filter("username", QuerySpec.Operator.CONTAINS, prefix, null));
        var page1 = read(1, 1, List.of("enabled,asc"), filters);
        var page2 = read(2, 1, List.of("enabled,asc"), filters);
        assertEquals(2, page1.getTotal()); assertEquals(1, page1.getItems().size());
        var expected = List.of(first, second).stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        assertEquals(expected, List.of(page1.getItems().getFirst().id(), page2.getItems().getFirst().id()));
        jdbc.update("UPDATE auth_user SET enabled=0,version=2 WHERE id=?", first.toString());
        var details = read(1, 1, List.of(), List.of(filter("id", QuerySpec.Operator.EQ, first, null))).getItems().getFirst();
        assertFalse(details.enabled()); assertEquals(2, details.version()); assertEquals(prefix + "-a", details.username());
        var changed = read(1, 20, List.of("version,desc"), List.of(filter("username", QuerySpec.Operator.CONTAINS, prefix, null),
                filter("version", QuerySpec.Operator.BETWEEN, 1L, 3L), filter("enabled", QuerySpec.Operator.EQ, false, null)));
        assertEquals(List.of(details), changed.getItems());
        assertEquals(0, read(1, 1, List.of(), List.of(filter("id", QuerySpec.Operator.EQ, UUID.randomUUID(), null))).getTotal());
        var json = JsonMapper.builder().build().valueToTree(details);
        assertEquals(4, json.size()); assertFalse(json.has("passwordHash")); assertFalse(json.has("bootstrapSlot"));
        assertFalse(Arrays.stream(AccountManagementView.class.getDeclaredFields()).anyMatch(field -> field.getName().toLowerCase().contains("password")));
    }

    /** LIKE元字符按字面处理，注入文本不能扩大结果；用户名精确匹配区分大小写。 */
    @Test
    void bindsFiltersAndEscapesLikeWildcards() {
        UUID literal = account("_x"); account("ax");
        var result = read(1, 20, List.of("username,desc"), List.of(filter("username", QuerySpec.Operator.CONTAINS, prefix + "_", null)));
        assertEquals(List.of(literal), result.getItems().stream().map(AccountManagementQuery.Row::id).toList());
        for (String value : List.of("%", "!", "' OR 1=1 --")) {
            assertEquals(0, read(1, 20, List.of(), List.of(filter("username", QuerySpec.Operator.CONTAINS, prefix + value, null))).getTotal());
        }
        assertEquals(1, read(1, 20, List.of(), List.of(filter("username", QuerySpec.Operator.EQ, prefix + "_x", null))).getTotal());
        assertEquals(0, read(1, 20, List.of(), List.of(filter("username", QuerySpec.Operator.EQ, prefix.toUpperCase() + "_x", null))).getTotal());
    }

    /** 未知字段、非法操作或值类型在SQL前拒绝，页大小沿用公共200上限。 */
    @Test
    void rejectsUnapprovedFieldsAndBoundsPages() {
        assertThrows(IllegalArgumentException.class, () -> AccountManagementQuery.create(1, 20, List.of("passwordHash,asc"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> AccountManagementQuery.create(1, 20, List.of(), List.of(filter("bootstrapSlot", QuerySpec.Operator.EQ, 1, null))));
        assertThrows(IllegalArgumentException.class, () -> AccountManagementQuery.create(1, 20, List.of(), List.of(filter("enabled", QuerySpec.Operator.EQ, "true", null))));
        assertThrows(IllegalArgumentException.class, () -> AccountManagementQuery.create(1, 20, List.of(), List.of(filter("version", QuerySpec.Operator.BETWEEN, 2L, 1L))));
        assertEquals(200, AccountManagementQuery.create(1, 999, List.of(), List.of()).spec().getPageRequest().getPageSize());
    }

    /** 实际查询装配复用管理员检查，普通账号、匿名、停用管理员和写事务均不能读取。 */
    @Test
    void rejectsUnauthorizedReadersAndTransactions() {
        UUID ordinary = account("-ordinary"); var query = AccountManagementQuery.create(1, 20, List.of(), List.of());
        var forged = new QueryContext.Snapshot("account-management", Optional.of(ordinary.toString()), Set.of("auth.account.manage"));
        assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, () -> gateway.execute(query, forged)).getErrorCode());
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, () -> gateway.execute(query, QueryContext.anonymous("account-management"))).getErrorCode());
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", subjectId());
        try { assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, () -> gateway.execute(query, context())).getErrorCode()); }
        finally { jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", subjectId()); }
        assertThrows(IllegalStateException.class, () -> work.execute(() -> gateway.execute(query, context())));
    }

    /** 目标列表读取异常仍失败关闭，不传播底层SQL或连接信息。 */
    @Test
    void sanitizesStorageFailureAfterAuthorization() {
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(AccountManagementView.class)).thenThrow(new EasyQueryException("sensitive-account-list"));
        var handler = new AccountManagementQueryHandler(database, access);
        var failure = assertThrows(StratumException.class, () -> handler.handle(AccountManagementQuery.create(1, 20, List.of(), List.of()), context()));
        assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode()); assertNull(failure.getCause());
        assertFalse(failure.toString().contains("sensitive-account-list"));
    }

    /**
     * 保存随机用户名普通账号，清除临时密码数组。
     * @param suffix 测试用户名后缀
     * @return 新账号UUID
     */
    private UUID account(String suffix) {
        char[] secret = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create(prefix + suffix, passwords.hash(secret), false);
            UUID id = UUID.fromString(account.getId()); owned.add(id);
            work.execute(() -> { accounts.insert(account); return null; }); return id;
        } finally { Arrays.fill(secret, '\0'); }
    }

    /**
     * 从真实HTTP登录夹具的主体构造服务器上下文。
     * @return 当前管理员上下文
     */
    private QueryContext context() { return new QueryContext.Snapshot("account-management", Optional.of(subjectId()), Set.of()); }

    /**
     * 通过正式网关执行有界查询。
     * @param page 页码
     * @param size 页大小
     * @param sorts 排序
     * @param filters 条件
     * @return 无凭据账号结果
     */
    private QueryResult<AccountManagementQuery.Row> read(int page, int size, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        return gateway.execute(AccountManagementQuery.create(page, size, sorts, filters), context());
    }

    /**
     * 生成类型化测试条件。
     * @param name 白名单字段
     * @param operator 操作
     * @param value 下界或单值
     * @param upper 可选上界
     * @return 原始条件输入
     */
    private static QuerySpec.FilterInput filter(String name, QuerySpec.Operator operator, Object value, Object upper) {
        return new QuerySpec.FilterInput(name, operator, value, upper);
    }
}
