package com.patrick.stratum.domain.auth;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证角色仓库默认值、管理员不变量、独立版本及配置快照边界。 */
class RoleWarehouseScopeTest {
    /** 普通角色从空范围和独立版本0开始，不因存在角色而自动访问仓库。 */
    @Test
    void ordinaryRoleStartsWithoutWarehouseAccess() {
        String id = UUID.randomUUID().toString(); var configuration = RoleWarehouseScope.initial(id, "purchaser");
        assertEquals(id, configuration.getId()); assertEquals("purchaser", configuration.getRoleCode());
        assertEquals(0L, configuration.getVersion()); assertEquals(DataScopeType.CUSTOM, configuration.getScope().type());
        assertFalse(configuration.getScope().permits(UUID.randomUUID()));
    }

    /** 管理员始终ALL，配置入口连相同ALL替换也拒绝，重建非法配置不能放行。 */
    @Test
    void administratorScopeIsFixedAndCannotBeReconfigured() {
        String id = UUID.randomUUID().toString(); var configuration = RoleWarehouseScope.initial(id, "administrator");
        assertEquals(WarehouseScope.all(), configuration.getScope());
        assertThrows(IllegalArgumentException.class, () -> configuration.replace(WarehouseScope.custom(Set.of())));
        assertThrows(IllegalArgumentException.class, () -> configuration.replace(WarehouseScope.all()));
        assertThrows(IllegalArgumentException.class, () -> RoleWarehouseScope.restore(id, "administrator", WarehouseScope.custom(Set.of()), 0));
        assertTrue(configuration.getScope().permits(UUID.randomUUID()));
    }

    /** 普通角色整组切换ALL、指定和空集合，不隐式修改版本或先前不可变快照。 */
    @Test
    void replacesWholeScopeWithoutMutatingPreviousSnapshot() {
        var configuration = RoleWarehouseScope.initial(UUID.randomUUID().toString(), "purchaser");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(); var ids = new HashSet<>(Set.of(first));
        configuration.replace(WarehouseScope.custom(ids)); var before = configuration.getScope(); ids.add(second);
        assertFalse(before.permits(second));
        configuration.replace(WarehouseScope.all()); assertTrue(configuration.getScope().permits(second));
        assertTrue(before.permits(first)); assertFalse(before.permits(second));
        configuration.replace(WarehouseScope.custom(Set.of())); assertFalse(configuration.getScope().permits(first));
        assertEquals(0L, configuration.getVersion());
        assertThrows(NullPointerException.class, () -> configuration.replace(null));
        assertTrue(configuration.getScope().warehouseIds().isEmpty());
    }

    /** 独立版本只能递增，角色名称和API权限聚合不被仓库配置修改。 */
    @Test
    void maintainsIndependentMonotonicVersion() {
        var role = AuthorizationRole.create("purchaser", "采购员"); role.markPersisted(7);
        role.replacePermissions(Set.of(PermissionCatalog.PURCHASE_READ.permission()));
        var configuration = RoleWarehouseScope.restore(role.getId(), role.getCode(), WarehouseScope.all(), 3);
        configuration.replace(WarehouseScope.custom(Set.of())); configuration.markPersisted(4);
        assertEquals(4L, configuration.getVersion()); assertEquals(7L, role.getVersion());
        assertEquals(Set.of(PermissionCatalog.PURCHASE_READ.permission()), role.getPermissions());
        assertThrows(IllegalArgumentException.class, () -> configuration.markPersisted(4));
        assertThrows(IllegalArgumentException.class, () -> configuration.markPersisted(-1));
        assertEquals(4L, configuration.getVersion());
    }

    /** 配置不能用非规范角色身份、非法编码、空范围或负版本创建。 */
    @Test
    void rejectsInvalidIdentityAndRestoredState() {
        String id = UUID.randomUUID().toString();
        for (String invalid : new String[]{"bad", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"}) {
            assertThrows(IllegalArgumentException.class, () -> RoleWarehouseScope.initial(invalid, "purchaser"));
        }
        assertThrows(IllegalArgumentException.class, () -> RoleWarehouseScope.initial(null, "purchaser"));
        assertThrows(IllegalArgumentException.class, () -> RoleWarehouseScope.initial(id, "role.*"));
        assertThrows(NullPointerException.class, () -> RoleWarehouseScope.restore(id, "purchaser", null, 0));
        assertThrows(IllegalArgumentException.class, () -> RoleWarehouseScope.restore(id, "purchaser", WarehouseScope.all(), -1));
    }
}
