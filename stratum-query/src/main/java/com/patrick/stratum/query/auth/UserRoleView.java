package com.patrick.stratum.query.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.auth.proxy.UserRoleViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 只读账号角色关联投影，不提供写入操作。 */
@Getter
@Setter
@EntityProxy
@Table("auth_user_role")
public class UserRoleView implements ProxyEntityAvailable<UserRoleView, UserRoleViewProxy> {
    @Column(primaryKey = true)
    private String userId;
    @Column(primaryKey = true)
    private String roleId;
}
