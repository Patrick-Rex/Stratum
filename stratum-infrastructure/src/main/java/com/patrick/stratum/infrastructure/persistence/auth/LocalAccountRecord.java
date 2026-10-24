package com.patrick.stratum.infrastructure.persistence.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.UpdateIgnore;
import com.easy.query.core.annotation.Version;
import com.easy.query.core.basic.extension.version.VersionLongStrategy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.auth.proxy.LocalAccountRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 本地账号持久化映射；不生成包含凭据的toString，仅供内部仓储使用。 */
@Getter
@Setter
@EntityProxy
@Table("auth_user")
public class LocalAccountRecord implements ProxyEntityAvailable<LocalAccountRecord, LocalAccountRecordProxy> {
    @Column(primaryKey = true)
    private String id;
    @UpdateIgnore
    private String username;
    @UpdateIgnore
    private String passwordHash;
    private Boolean enabled;
    @UpdateIgnore
    private Integer bootstrapSlot;
    @Version(strategy = VersionLongStrategy.class)
    private Long version;
}
