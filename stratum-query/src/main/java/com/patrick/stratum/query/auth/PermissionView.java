package com.patrick.stratum.query.auth;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.auth.proxy.PermissionViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 只读权限标识投影，不加载管理显示字段。 */
@Getter
@Setter
@EntityProxy
@Table("auth_permission")
public class PermissionView implements ProxyEntityAvailable<PermissionView, PermissionViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String code;
    private String type;
}
