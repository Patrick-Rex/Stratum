package com.patrick.stratum.query.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.auth.proxy.RoleViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 角色只读投影，提供管理查询所需的标识、编码、名称和并发版本。 */
@Getter
@Setter
@EntityProxy
@Table("auth_role")
public class RoleView implements ProxyEntityAvailable<RoleView, RoleViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String code;
    private String name;
    private Long version;
}
