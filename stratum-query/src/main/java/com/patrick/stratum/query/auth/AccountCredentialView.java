package com.patrick.stratum.query.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.auth.proxy.AccountCredentialViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 认证内部只读投影，不包含初始化权限标记，不提供HTTP控制器或带字段的toString。 */
@Getter
@Setter
@EntityProxy
@Table("auth_user")
public class AccountCredentialView implements ProxyEntityAvailable<AccountCredentialView, AccountCredentialViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String username;
    private String passwordHash;
    private Boolean enabled;
}
