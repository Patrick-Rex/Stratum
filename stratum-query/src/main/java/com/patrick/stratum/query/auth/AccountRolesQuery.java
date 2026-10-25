package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 账号角色有界详情查询，固定目标ID，关系与持久化版本由同一语句读取。 */
public final class AccountRolesQuery implements Query<AccountRolesQuery.Row> {
    /** 唯一允许的目标条件。 */
    public enum Field { ID }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of("id",
            new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false));
    private final QuerySpec<Field> spec;

    /**
     * 保存固定单条详情规范。
     * @param spec 已验证规范
     */
    private AccountRolesQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 创建有界关系详情请求，空结果表示目标不存在，非空结果可包含空关系集合。
     * @param id 目标UUID
     * @return 固定一页一条详情的查询
     * @apiNote 示例：{@code AccountRolesQuery.create(id)}交给QueryGateway并传入可信管理员上下文。
     */
    public static AccountRolesQuery create(UUID id) {
        return new AccountRolesQuery(QuerySpec.create(1, 1, List.of(),
                List.of(new QuerySpec.FilterInput("id", QuerySpec.Operator.EQ, Objects.requireNonNull(id), null)), FIELDS));
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }

    /**
     * 不可变的完整关系快照，版本用于后续乐观写入。
     * @param id 目标UUID
     * @param version 目标持久化版本
     * @param roleIds 完整关系集合
     */
    public record Row(UUID id, long version, Set<UUID> roleIds) {
        /**
         * 复制集合并拒绝超量或非法持久化版本。
         * @param id 目标UUID
         * @param version 非负持久化版本
         * @param roleIds 完整关系集合
         * @apiNote 示例：{@code new Row(id, 0, Set.of())}表示已存在且无关系的目标。
         */
        public Row {
            Objects.requireNonNull(id); roleIds = Set.copyOf(roleIds);
            if (version < 0 || roleIds.size() > AuthorizationAdministrationRepository.MAX_ROLES_PER_ACCOUNT) throw new IllegalArgumentException("关系快照超出边界");
        }
    }
}
