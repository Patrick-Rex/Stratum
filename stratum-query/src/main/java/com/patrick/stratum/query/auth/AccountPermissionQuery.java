package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 内部单账号有效权限查询，固定一页一个快照，禁止客户端指定过滤表达式。 */
public final class AccountPermissionQuery implements Query<AccountPermissions.Snapshot> {
    /** 只允许按账号ID精确读取。 */
    public enum Field { USER_ID }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of("userId",
            new QuerySpec.Field<>(Field.USER_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false));
    private final QuerySpec<Field> spec;

    /**
     * 保存不可变规范。
     * @param spec 已验证规范
     */
    private AccountPermissionQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 构造精确账号查询，权限集合上界由处理器与快照共同保证。
     * @param userId 可信账号UUID
     * @return 固定分页查询
     * @apiNote 示例：{@code AccountPermissionQuery.create(identity.userId())}仅通过QueryGateway执行。
     */
    public static AccountPermissionQuery create(UUID userId) {
        return new AccountPermissionQuery(QuerySpec.create(1, 1, List.of(),
                List.of(new QuerySpec.FilterInput("userId", QuerySpec.Operator.EQ, Objects.requireNonNull(userId), null)), FIELDS));
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }
}
