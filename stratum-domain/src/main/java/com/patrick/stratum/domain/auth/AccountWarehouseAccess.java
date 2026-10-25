package com.patrick.stratum.domain.auth;

import java.util.UUID;

/** 当前账号目标仓库访问只读端口，范围由权威角色关系决定，不确认业务资源是否存在。 */
public interface AccountWarehouseAccess {
    /**
     * 检查当前启用账号是否具有目标仓库范围，未知、停用或未授权均返回false，存储故障不得当作成功。
     * @param userId 服务器可信账号UUID
     * @param warehouseId 目标仓库UUID
     * @param traceId 服务端关联标识
     * @return 是否满足仓库范围，不表示已满足API权限
     * @apiNote 示例：{@code access.permits(identity.userId(), warehouseId, traceId)}；实现经QueryGateway且必须在事务外使用。
     */
    boolean permits(UUID userId, UUID warehouseId, String traceId);
}
