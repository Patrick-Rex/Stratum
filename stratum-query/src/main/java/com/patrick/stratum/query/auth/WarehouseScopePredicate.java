package com.patrick.stratum.query.auth;

import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.query.QueryContext;

/** 数据库范围谓词：以当前启用账号的角色关系计算ALL或指定仓库并集，不物化或截断集合。 */
public final class WarehouseScopePredicate {
    /**
     * 参数0为固定目标列或绑定仓库UUID，参数1为服务端绑定用户UUID；调用方必须关闭SQL参数日志。
     * 表名与关联均为后端常量，不接收客户端SQL或授权集合。
     */
    public static final String SQL = """
            EXISTS (
                SELECT 1 FROM auth_user scope_user
                INNER JOIN auth_user_role scope_member ON scope_member.user_id = scope_user.id
                INNER JOIN auth_role_warehouse_scope scope_config ON scope_config.role_id = scope_member.role_id
                LEFT JOIN auth_role_warehouse scope_warehouse
                    ON scope_warehouse.role_id = scope_config.role_id AND scope_warehouse.warehouse_id = {0}
                WHERE scope_user.id = {1} AND scope_user.enabled = 1
                    AND (scope_config.scope_type = 'ALL'
                        OR (scope_config.scope_type = 'CUSTOM' AND scope_warehouse.warehouse_id IS NOT NULL))
            )
            """;

    /**
     * 取得服务器查询上下文的规范UUID主体，忽略自报权限字符串。
     * @param context 服务器构造的查询上下文
     * @return 规范UUID文本
     * @throws StratumException 匿名401或非规范主体403
     * @apiNote 示例：{@code String actor = WarehouseScopePredicate.requireActor(context)}。
     */
    public static String requireActor(QueryContext context) {
        String actor = context.operatorId().orElseThrow(() -> new StratumException(CommonErrorCode.UNAUTHORIZED));
        try {
            if (UUID.fromString(actor).toString().equals(actor)) return actor;
        } catch (IllegalArgumentException invalid) { /* 非UUID主体统一拒绝，不回显输入。 */ }
        throw new StratumException(CommonErrorCode.FORBIDDEN);
    }

    /** 仅提供固定参数化谓词，不创建实例。 */
    private WarehouseScopePredicate() { }
}
