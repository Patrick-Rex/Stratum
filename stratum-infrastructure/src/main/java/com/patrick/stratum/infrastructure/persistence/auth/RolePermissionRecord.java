package com.patrick.stratum.infrastructure.persistence.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.auth.proxy.RolePermissionRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 角色权限关系持久化映射，不包含隐式授权规则。 */
@Getter
@Setter
@EntityProxy
@Table("auth_role_permission")
public class RolePermissionRecord implements ProxyEntityAvailable<RolePermissionRecord, RolePermissionRecordProxy> {
    @Column(primaryKey = true)
    private String roleId;
    @Column(primaryKey = true)
    private String permissionId;
}
