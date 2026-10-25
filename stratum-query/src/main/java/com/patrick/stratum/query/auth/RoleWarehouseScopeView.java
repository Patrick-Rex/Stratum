package com.patrick.stratum.query.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.auth.proxy.RoleWarehouseScopeViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 角色仓库配置的专用只读投影，不提供写入或隐式授权。 */
@Getter
@Setter
@EntityProxy
@Table("auth_role_warehouse_scope")
public class RoleWarehouseScopeView implements ProxyEntityAvailable<RoleWarehouseScopeView, RoleWarehouseScopeViewProxy> {
    @Column(primaryKey = true)
    private String roleId;
    private String scopeType;
    private Long version;
}
