package com.patrick.stratum.infrastructure.persistence.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.auth.proxy.RoleWarehouseRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 指定仓库关系映射，类别外键与CUSTOM配置外键防止越界引用。 */
@Getter
@Setter
@EntityProxy
@Table("auth_role_warehouse")
public class RoleWarehouseRecord implements ProxyEntityAvailable<RoleWarehouseRecord, RoleWarehouseRecordProxy> {
    @Column(primaryKey = true)
    private String roleId;
    @Column(primaryKey = true)
    private String warehouseId;
    private String scopeType = "CUSTOM";
    private String warehouseKind = "WAREHOUSE";
}
