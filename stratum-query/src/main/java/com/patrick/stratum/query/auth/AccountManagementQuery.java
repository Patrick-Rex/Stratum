package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 管理账号分页及ID详情查询，固定字段白名单且结果不含凭据或初始化资格。 */
public final class AccountManagementQuery implements Query<AccountManagementQuery.Row> {
    /** 只允许查询和排序公开管理字段。 */
    public enum Field { ID, USERNAME, ENABLED, VERSION }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of(
            "id", new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), true),
            "username", new QuerySpec.Field<>(Field.USERNAME, String.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.CONTAINS), true),
            "enabled", new QuerySpec.Field<>(Field.ENABLED, Boolean.class, Set.of(QuerySpec.Operator.EQ), true),
            "version", new QuerySpec.Field<>(Field.VERSION, Long.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.BETWEEN), true));
    private final QuerySpec<Field> spec;

    /**
     * 保存不可变查询规范。
     * @param spec 已验证白名单规范
     */
    private AccountManagementQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 构造分页查询，ID精确条件亦用于读取账号详情，未知账号返回空页。
     * @param page 页码
     * @param pageSize 页大小，最大200
     * @param sorts 白名单排序
     * @param filters 白名单条件
     * @return 不包含任意SQL表达式的查询
     * @apiNote 示例：{@code AccountManagementQuery.create(1, 20, List.of("username,asc"), List.of())}。
     */
    public static AccountManagementQuery create(int page, int pageSize, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        return new AccountManagementQuery(QuerySpec.create(page, pageSize, sorts, filters, FIELDS));
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }

    /**
     * 账号管理不可变结果，版本与启停/成员替换写用例共用。
     * @param id 账号UUID
     * @param username 固定登录名
     * @param enabled 当前启用状态
     * @param version 当前持久化版本
     */
    public record Row(UUID id, String username, boolean enabled, long version) { }
}
