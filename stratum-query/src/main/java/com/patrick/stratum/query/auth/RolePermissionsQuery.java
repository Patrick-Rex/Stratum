package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 角色权限有界详情查询，固定目标ID，关系与持久化版本由同一语句读取。 */
public final class RolePermissionsQuery implements Query<RolePermissionsQuery.Row> {
    /** 唯一允许的目标条件。 */
    public enum Field { ID }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of("id",
            new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false));
    private final QuerySpec<Field> spec;

    /**
     * 保存固定单条详情规范。
     * @param spec 已验证规范
     */
    private RolePermissionsQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 创建有界关系详情请求，空结果表示目标不存在，非空结果可包含空关系集合。
     * @param id 目标UUID
     * @return 固定一页一条详情的查询
     * @apiNote 示例：{@code RolePermissionsQuery.create(id)}交给QueryGateway并传入可信管理员上下文。
     */
    public static RolePermissionsQuery create(UUID id) {
        return new RolePermissionsQuery(QuerySpec.create(1, 1, List.of(),
                List.of(new QuerySpec.FilterInput("id", QuerySpec.Operator.EQ, Objects.requireNonNull(id), null)), FIELDS));
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }

    /**
     * 不可变的完整关系快照，版本用于后续乐观写入。
     * @param id 目标UUID
     * @param version 目标持久化版本
     * @param permissions 完整关系集合
     */
    public record Row(UUID id, long version, Set<Permission> permissions) {
        /**
         * 复制集合并拒绝超量或非法持久化版本。
         * @param id 目标UUID
         * @param version 非负持久化版本
         * @param permissions 完整关系集合
         * @apiNote 示例：{@code new Row(id, 0, Set.of())}表示已存在且无关系的目标。
         */
        public Row {
            Objects.requireNonNull(id); permissions = Set.copyOf(permissions);
            if (version < 0 || permissions.size() > AccountPermissions.Snapshot.MAX_PERMISSIONS) throw new IllegalArgumentException("关系快照超出边界");
        }
    }
}
