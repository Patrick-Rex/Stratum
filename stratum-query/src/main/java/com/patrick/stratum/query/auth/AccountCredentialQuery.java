package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.patrick.stratum.domain.auth.AccountCredentials;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 内部精确凭据查询，固定一页一条，不接受动态过滤、分页或排序。 */
public final class AccountCredentialQuery implements Query<AccountCredentials.Snapshot> {
    /** 内部白名单仅允许用户名等值匹配。 */
    public enum Field { USERNAME }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of("username",
            new QuerySpec.Field<>(Field.USERNAME, String.class, Set.of(QuerySpec.Operator.EQ), false));
    private final QuerySpec<Field> spec;

    /**
     * 保存已验证的不可变规范。
     * @param spec 精确查询规范
     */
    private AccountCredentialQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 构造内部单账号查询，输入保留精确大小写。
     * @param username 登录标识
     * @return 有界查询
     * @apiNote 示例：{@code AccountCredentialQuery.create(username)}仅经QueryGateway使用。
     */
    public static AccountCredentialQuery create(String username) {
        return new AccountCredentialQuery(QuerySpec.create(1, 1, List.of(),
                List.of(new QuerySpec.FilterInput("username", QuerySpec.Operator.EQ, LocalAccount.requireUsername(username), null)), FIELDS));
    }

    /** {@inheritDoc} */
    @Override
    public QuerySpec<Field> spec() { return spec; }
}
