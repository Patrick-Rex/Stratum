package com.patrick.stratum.query;

import java.util.Optional;
import java.util.Set;

import com.patrick.stratum.common.helper.ValidationHelper;

/** 服务器构造的显式查询上下文契约，身份认证和数据范围约束在安全节点接入。 */
public interface QueryContext {
    /**
     * 获取本次查询链路标识。
     * @return 非空安全日志标识
     * @apiNote 示例：context.traceId()。
     */
    String traceId();

    /**
     * 获取已认证操作者，匿名请求为空。
     * @return 可选操作者标识
     * @apiNote 示例：context.operatorId().orElseThrow()。
     */
    Optional<String> operatorId();

    /**
     * 获取服务器已解析的权限集合；存在该契约不代表已经实施授权。
     * @return 不可变权限集合
     * @apiNote 示例：context.permissions().contains("erp.material.read")。
     */
    Set<String> permissions();

    /**
     * 创建无身份和权限的上下文，用于安全组件接入前的本地基架验证。
     * @param traceId 链路标识
     * @return 匿名上下文
     * @apiNote 示例：QueryContext.anonymous("trace-001")。
     */
    static QueryContext anonymous(String traceId) { return new Snapshot(traceId, Optional.empty(), Set.of()); }

    /**
     * 不可变上下文快照，适合显式跨线程传递；不能从 HTTP 请求体直接绑定身份和权限。
     * @param traceId 链路标识
     * @param operatorId 已认证操作者或空值
     * @param permissions 可信权限集合
     */
    record Snapshot(String traceId, Optional<String> operatorId, Set<String> permissions) implements QueryContext {
        /**
         * 校验日志标识并复制集合，防止请求间共享可变权限状态。
         * @param traceId 1 到 64 字符的安全日志标识
         * @param operatorId 已认证操作者或空值
         * @param permissions 可信权限集合，匿名时必须为空
         * @throws IllegalArgumentException 上下文参数非法时抛出
         * @apiNote 示例：new Snapshot("trace-001", Optional.of("user-id"), Set.of("erp.material.read"))。
         */
        public Snapshot {
            traceId = ValidationHelper.requireNonBlank(traceId, "traceId");
            if (!traceId.matches("[A-Za-z0-9_.-]{1,64}")) { throw new IllegalArgumentException("查询 traceId 格式非法"); }
            operatorId = ValidationHelper.requireNonNull(operatorId, "operatorId")
                    .map(id -> ValidationHelper.requireNonBlank(id, "operatorId"));
            permissions = Set.copyOf(permissions);
            if (operatorId.isEmpty() && !permissions.isEmpty()) { throw new IllegalArgumentException("匿名上下文不能携带权限"); }
            for (String permission : permissions) {
                if (!permission.matches("[a-z][a-z0-9_.:-]{0,127}")) {
                    throw new IllegalArgumentException("查询权限标识格式非法");
                }
            }
        }
    }
}
