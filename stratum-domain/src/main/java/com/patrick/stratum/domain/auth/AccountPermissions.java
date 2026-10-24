package com.patrick.stratum.domain.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 账号有效权限只读端口；实现经QueryGateway读取权威关系，不采信HTTP或JWT自报权限。 */
public interface AccountPermissions {
    /**
     * 读取账号状态及角色关联的去重权限，缺失账号返回空，不缓存授权成功。
     * @param userId 服务器可信账号UUID
     * @param traceId 服务器关联标识
     * @return 存在账号的有界不可变快照
     * @apiNote 示例：{@code permissions.findByUserId(identity.userId(), traceId)}；必须在事务外调用。
     */
    Optional<Snapshot> findByUserId(UUID userId, String traceId);

    /**
     * 值对象：一次权威读取的账号状态及权限快照，不包含凭据或默认管理员绕过标记。
     * @param userId 账号UUID
     * @param enabled 账号是否启用
     * @param permissions 去重权限集合，最大1024项以限制单次授权读取和内存
     */
    record Snapshot(UUID userId, boolean enabled, Set<Permission> permissions) {
        public static final int MAX_PERMISSIONS = 1024;

        /**
         * 验证读取上界并防御性复制，禁止静默截断权限集合。
         * @param userId 账号UUID
         * @param enabled 启用状态
         * @param permissions 权限集合
         * @apiNote 示例：{@code new Snapshot(userId, true, Set.of(permission))}只由可信查询构造。
         */
        public Snapshot {
            Objects.requireNonNull(userId, "账号标识不能为空");
            Objects.requireNonNull(permissions, "权限集合不能为空");
            if (permissions.size() > MAX_PERMISSIONS) throw new IllegalArgumentException("权限集合超出读取上限");
            permissions = Set.copyOf(permissions);
        }

        /**
         * 仅启用账号且类型与编码均精确匹配时允许访问。
         * @param required 所需权限
         * @return 是否拥有该权限，不表示已通过仓库数据范围校验
         * @apiNote 示例：{@code snapshot.permits(new Permission("erp.material.read", Permission.Type.API))}。
         */
        public boolean permits(Permission required) {
            Objects.requireNonNull(required, "所需权限不能为空");
            return enabled && permissions.contains(required);
        }

        /**
         * 按类型提取不可变编码集合，禁用账号始终为空。
         * @param type 精确权限类型
         * @return 可用于服务端检查或前端显示的编码，不包含另一类型
         * @apiNote 示例：{@code snapshot.codes(Permission.Type.FEAT)}供auth/me显示，不能替代API校验。
         */
        public Set<String> codes(Permission.Type type) {
            Objects.requireNonNull(type, "权限类型不能为空");
            if (!enabled) return Set.of();
            return permissions.stream().filter(permission -> permission.type() == type)
                    .map(Permission::code).collect(Collectors.toUnmodifiableSet());
        }
    }
}
