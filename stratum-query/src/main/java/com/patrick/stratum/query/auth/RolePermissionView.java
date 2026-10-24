package com.patrick.stratum.query.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.auth.proxy.RolePermissionViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 只读角色权限关联投影。 */
@Getter
@Setter
@EntityProxy
@Table("auth_role_permission")
public class RolePermissionView implements ProxyEntityAvailable<RolePermissionView, RolePermissionViewProxy> {
    @Column(primaryKey = true)
    private String roleId;
    @Column(primaryKey = true)
    private String permissionId;
}
