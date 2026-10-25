package com.patrick.stratum.query.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.auth.proxy.RoleWarehouseViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 角色仓库配置的专用只读投影，不提供写入或隐式授权。 */
@Getter
@Setter
@EntityProxy
@Table("auth_role_warehouse")
public class RoleWarehouseView implements ProxyEntityAvailable<RoleWarehouseView, RoleWarehouseViewProxy> {
    @Column(primaryKey = true)
    private String roleId;
    @Column(primaryKey = true)
    private String warehouseId;
}
