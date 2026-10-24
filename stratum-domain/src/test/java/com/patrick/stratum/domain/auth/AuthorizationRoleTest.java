package com.patrick.stratum.domain.auth;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证角色固定身份、权限目录及管理员角色不变量。 */
class AuthorizationRoleTest {
    /** 普通角色无默认权限，名称可修改，复制的权限不受源集合改变影响。 */
    @Test
    void ordinaryRoleKeepsIdentityAndOwnsImmutableRegisteredPermissions() {
        var role = AuthorizationRole.create("purchaser", "采购员");
        assertNotNull(UUID.fromString(role.getId())); assertNull(role.getVersion()); assertFalse(role.isAdministrator());
        assertTrue(role.getPermissions().isEmpty());
        role.rename("采购主管"); assertEquals("采购主管", role.getName()); assertEquals("purchaser", role.getCode());
        var source = new HashSet<>(Set.of(PermissionCatalog.PURCHASE_READ.permission()));
        role.replacePermissions(source); source.clear(); assertEquals(1, role.getPermissions().size());
        assertThrows(UnsupportedOperationException.class, () -> role.getPermissions().clear());
        assertThrows(IllegalArgumentException.class, () -> role.replacePermissions(Set.of(new Permission("unknown", Permission.Type.API))));
        assertEquals(1, role.getPermissions().size());
        role.replacePermissions(Set.of()); assertTrue(role.getPermissions().isEmpty());
    }

    /** 管理员权限只由目录同步，普通创建/替换不能占用或削减其权限。 */
    @Test
    void administratorOwnsCatalogWithoutImplicitAccountAuthorization() {
        assertThrows(IllegalArgumentException.class, () -> AuthorizationRole.create("administrator", "冒用"));
        var role = AuthorizationRole.administrator(); assertTrue(role.isAdministrator());
        assertEquals(PermissionCatalog.permissions(), role.getPermissions());
        assertThrows(IllegalArgumentException.class, () -> role.replacePermissions(Set.of()));
        var restored = AuthorizationRole.restore(role.getId(), role.getCode(), role.getName(), Set.of(), 0);
        assertTrue(restored.getPermissions().isEmpty()); restored.synchronizeCatalog();
        assertEquals(PermissionCatalog.permissions(), restored.getPermissions());
        assertThrows(IllegalArgumentException.class, () -> AuthorizationRole.create("ordinary", "普通").synchronizeCatalog());
    }

    /** 标识、名称、集合与版本有界，非法变更不覆盖既有值。 */
    @Test
    void validatesRoleBoundariesAndMonotonicVersions() {
        for (String code : new String[]{"", "Upper", "a..b", "a\n", "a".repeat(97)}) {
            assertThrows(IllegalArgumentException.class, () -> AuthorizationRole.create(code, "角色"));
        }
        var role = AuthorizationRole.create("a".repeat(96), "名".repeat(128));
        for (String name : new String[]{"", " ", "a\n", "a".repeat(129)}) assertThrows(IllegalArgumentException.class, () -> role.rename(name));
        assertEquals(128, role.getName().length());
        role.markPersisted(0); role.markPersisted(1);
        assertThrows(IllegalArgumentException.class, () -> role.markPersisted(1));
        assertThrows(IllegalArgumentException.class, () -> role.markPersisted(-1));
        assertThrows(IllegalArgumentException.class, () -> AuthorizationRole.restore("bad", "role", "角色", Set.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> AuthorizationRole.restore(role.getId(), "role", "角色", Set.of(), -1));
        var oversized = IntStream.range(0, 1025).mapToObj(i -> new Permission("test." + i, Permission.Type.API)).collect(Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () -> AuthorizationRole.restore(role.getId(), "role", "角色", oversized, 0));
        assertThrows(NullPointerException.class, () -> role.replacePermissions(null));
    }

    /** 所有登记条目唯一且不可变，API与FEAT精确类型不能互换。 */
    @Test
    void catalogIsUniqueBoundedAndTyped() {
        assertEquals(PermissionCatalog.values().length, PermissionCatalog.permissions().size());
        assertTrue(PermissionCatalog.permissions().size() <= AccountPermissions.Snapshot.MAX_PERMISSIONS);
        assertThrows(UnsupportedOperationException.class, () -> PermissionCatalog.permissions().clear());
        for (var entry : PermissionCatalog.values()) assertFalse(entry.displayName().isBlank());
        var api = PermissionCatalog.MATERIAL_READ.permission();
        assertFalse(PermissionCatalog.permissions().contains(new Permission(api.code(), Permission.Type.FEAT)));
    }
}
