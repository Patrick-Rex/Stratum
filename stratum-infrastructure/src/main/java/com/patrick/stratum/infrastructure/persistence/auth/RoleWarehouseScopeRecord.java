package com.patrick.stratum.infrastructure.persistence.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.Version;
import com.easy.query.core.basic.extension.version.VersionLongStrategy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.auth.proxy.RoleWarehouseScopeRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 角色仓库配置的一对一持久化映射，版本独立于角色API权限。 */
@Getter
@Setter
@EntityProxy
@Table("auth_role_warehouse_scope")
public class RoleWarehouseScopeRecord implements ProxyEntityAvailable<RoleWarehouseScopeRecord, RoleWarehouseScopeRecordProxy> {
    @Column(primaryKey = true)
    private String roleId;
    private String scopeType;
    @Version(strategy = VersionLongStrategy.class)
    private Long version;
}
