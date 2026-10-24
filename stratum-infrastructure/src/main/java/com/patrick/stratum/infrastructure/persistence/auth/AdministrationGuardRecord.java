package com.patrick.stratum.infrastructure.persistence.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.auth.proxy.AdministrationGuardRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 管理协调行的持久化映射，锁由Application事务生命周期释放。 */
@Getter
@Setter
@EntityProxy
@Table("auth_administration_guard")
public class AdministrationGuardRecord implements ProxyEntityAvailable<AdministrationGuardRecord, AdministrationGuardRecordProxy> {
    @Column(primaryKey = true)
    private Integer singletonId;
    private Boolean initialized;
}
