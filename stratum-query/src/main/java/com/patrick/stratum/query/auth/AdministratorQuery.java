package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 内部当前管理员资格查询，查询目标必须与服务器上下文操作者相同。 */
public final class AdministratorQuery implements Query<Boolean> {
    /** 固定的操作者条件，不接受任意过滤表达。 */
    public enum Field { USER_ID }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of("userId",
            new QuerySpec.Field<>(Field.USER_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false));
    private final QuerySpec<Field> spec;

    /**
     * 保存固定分页及操作者规范。
     * @param spec 已验证查询规范
     */
    private AdministratorQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 构造只返回当前管理员资格的内部查询，不读取凭据。
     * @param userId 服务器已认证账号
     * @return 一页一个布尔结果的查询
     * @apiNote 示例：{@code AdministratorQuery.create(identity.userId())}交给QueryGateway。
     */
    public static AdministratorQuery create(UUID userId) {
        return new AdministratorQuery(QuerySpec.create(1, 1, List.of(),
                List.of(new QuerySpec.FilterInput("userId", QuerySpec.Operator.EQ, Objects.requireNonNull(userId), null)), FIELDS));
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }
}
