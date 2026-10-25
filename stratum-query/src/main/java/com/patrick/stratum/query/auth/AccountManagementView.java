package com.patrick.stratum.query.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.auth.proxy.AccountManagementViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 管理账号只读投影，只映射可公开的管理字段，不包含凭据、初始化标记或会话。 */
@Getter
@Setter
@EntityProxy
@Table("auth_user")
public class AccountManagementView implements ProxyEntityAvailable<AccountManagementView, AccountManagementViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String username;
    private Boolean enabled;
    private Long version;
}
