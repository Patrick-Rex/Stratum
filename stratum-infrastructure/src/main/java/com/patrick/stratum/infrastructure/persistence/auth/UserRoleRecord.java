package com.patrick.stratum.infrastructure.persistence.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.auth.proxy.UserRoleRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 账号角色关系持久化映射，两列共同构成唯一关联。 */
@Getter
@Setter
@EntityProxy
@Table("auth_user_role")
public class UserRoleRecord implements ProxyEntityAvailable<UserRoleRecord, UserRoleRecordProxy> {
    @Column(primaryKey = true)
    private String userId;
    @Column(primaryKey = true)
    private String roleId;
}
