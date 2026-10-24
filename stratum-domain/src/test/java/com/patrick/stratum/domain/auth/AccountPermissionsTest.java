package com.patrick.stratum.domain.auth;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证权限类型隔离、精确匹配及有界不可变快照，不引入框架依赖。 */
class AccountPermissionsTest {
    /** API和FEAT的同名编码不互相授予权限，也不隐式覆盖前缀。 */
    @Test
    void distinguishesExactApiAndFeaturePermissions() {
        var api = new Permission("erp.material.read", Permission.Type.API);
        var snapshot = new AccountPermissions.Snapshot(UUID.randomUUID(), true, Set.of(api));
        assertTrue(snapshot.permits(api));
        assertFalse(snapshot.permits(new Permission(api.code(), Permission.Type.FEAT)));
        assertFalse(snapshot.permits(new Permission("erp.material", Permission.Type.API)));
        assertFalse(snapshot.permits(new Permission("erp.material.write", Permission.Type.API)));
        assertEquals(Set.of(api.code()), snapshot.codes(Permission.Type.API));
        assertTrue(snapshot.codes(Permission.Type.FEAT).isEmpty());
    }

    /** 禁用或无授权账号不获得任何默认权限。 */
    @Test
    void disabledOrEmptyAccountsCannotPermitOperations() {
        var permission = new Permission("erp.read", Permission.Type.API);
        var disabled = new AccountPermissions.Snapshot(UUID.randomUUID(), false, Set.of(permission));
        assertFalse(disabled.permits(permission)); assertTrue(disabled.codes(Permission.Type.API).isEmpty());
        assertFalse(new AccountPermissions.Snapshot(UUID.randomUUID(), true, Set.of()).permits(permission));
    }

    /** 权限源集合和提取集合不能改变快照，超界读取不得静默截断。 */
    @Test
    void snapshotsAreImmutableAndBounded() {
        var permission = new Permission("erp.read", Permission.Type.API);
        var source = new HashSet<>(Set.of(permission));
        var snapshot = new AccountPermissions.Snapshot(UUID.randomUUID(), true, source);
        source.clear(); assertTrue(snapshot.permits(permission));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.permissions().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.codes(Permission.Type.API).clear());
        var maximum = IntStream.range(0, AccountPermissions.Snapshot.MAX_PERMISSIONS)
                .mapToObj(index -> new Permission("permission." + index, Permission.Type.API)).collect(Collectors.toSet());
        assertEquals(1024, new AccountPermissions.Snapshot(UUID.randomUUID(), true, maximum).permissions().size());
        maximum.add(new Permission("permission.extra", Permission.Type.API));
        assertThrows(IllegalArgumentException.class, () -> new AccountPermissions.Snapshot(UUID.randomUUID(), true, maximum));
    }

    /** 编码不接受通配符、控制字符、空段、大小写替换或超长值。 */
    @Test
    void rejectsMalformedIdentifiersAndMissingState() {
        for (String code : new String[]{"", "*", "erp.*", "ERP.read", " erp.read", "erp.read ", "erp..read", "erp/read", "a\n", "a".repeat(97)}) {
            assertThrows(IllegalArgumentException.class, () -> new Permission(code, Permission.Type.API));
        }
        assertEquals(96, new Permission("a".repeat(96), Permission.Type.API).code().length());
        assertThrows(IllegalArgumentException.class, () -> new Permission(null, Permission.Type.API));
        assertThrows(NullPointerException.class, () -> new Permission("erp.read", null));
        assertThrows(NullPointerException.class, () -> new AccountPermissions.Snapshot(null, true, Set.of()));
        assertThrows(NullPointerException.class, () -> new AccountPermissions.Snapshot(UUID.randomUUID(), true, null));
    }
}
