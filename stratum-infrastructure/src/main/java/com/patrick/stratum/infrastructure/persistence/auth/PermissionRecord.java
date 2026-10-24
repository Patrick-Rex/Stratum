package com.patrick.stratum.infrastructure.persistence.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.auth.proxy.PermissionRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 权限持久化映射，API与FEAT编码分别精确保存。 */
@Getter
@Setter
@EntityProxy
@Table("auth_permission")
public class PermissionRecord implements ProxyEntityAvailable<PermissionRecord, PermissionRecordProxy> {
    @Column(primaryKey = true)
    private String id;
    private String code;
    private String type;
    private String name;
}
