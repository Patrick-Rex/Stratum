package com.patrick.stratum.infrastructure.persistence.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.UpdateIgnore;
import com.easy.query.core.annotation.Version;
import com.easy.query.core.basic.extension.version.VersionLongStrategy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.auth.proxy.RoleRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 角色持久化映射，仅描述存储结构，不赋予默认权限。 */
@Getter
@Setter
@EntityProxy
@Table("auth_role")
public class RoleRecord implements ProxyEntityAvailable<RoleRecord, RoleRecordProxy> {
    @Column(primaryKey = true)
    private String id;
    @UpdateIgnore
    private String code;
    private String name;
    @Version(strategy = VersionLongStrategy.class)
    private Long version;
}
