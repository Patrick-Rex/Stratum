package com.patrick.stratum.domain.auth;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 仓库范围值对象的失败关闭、精确匹配、不可变及容量边界验证，不绑定账号或角色策略。 */
class WarehouseScopeTest {
    /** 全部范围允许未预先枚举的仓库，但拒绝没有目标的检查。 */
    @Test
    void allAllowsAnyExplicitWarehouseWithoutEnumeration() {
        var scope = WarehouseScope.all(); assertEquals(DataScopeType.ALL, scope.type());
        assertTrue(scope.warehouseIds().isEmpty());
        assertTrue(scope.permits(UUID.randomUUID())); assertTrue(scope.permits(UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> scope.permits(null));
    }

    /** 指定范围按完整UUID匹配，空集合始终不授予访问权。 */
    @Test
    void customMatchesOnlyExactWarehousesAndEmptyDenies() {
        var first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var scope = WarehouseScope.custom(Set.of(first));
        assertTrue(scope.permits(first)); assertFalse(scope.permits(second));
        assertFalse(WarehouseScope.custom(Set.of()).permits(first));
        assertThrows(NullPointerException.class, () -> scope.permits(null));
    }

    /** 修改原集合或访问器返回集合均不能扩大已构造范围。 */
    @Test
    void copiesAndProtectsWarehouseMembership() {
        UUID allowed = UUID.randomUUID(), excluded = UUID.randomUUID(); var source = new HashSet<>(Set.of(allowed));
        var scope = WarehouseScope.custom(source); source.clear(); source.add(excluded);
        assertTrue(scope.permits(allowed)); assertFalse(scope.permits(excluded));
        assertThrows(UnsupportedOperationException.class, () -> scope.warehouseIds().add(excluded));
        assertEquals(scope, WarehouseScope.custom(Set.of(allowed)));
    }

    /** 未定义的组织归属不能隐式转成全部权限，矛盾状态和空UUID均拒绝。 */
    @Test
    void rejectsUnsupportedTypesAndAmbiguousState() {
        for (var type : DataScopeType.values()) {
            if (type != DataScopeType.ALL && type != DataScopeType.CUSTOM) {
                assertThrows(IllegalArgumentException.class, () -> new WarehouseScope(type, Set.of()));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new WarehouseScope(DataScopeType.ALL, Set.of(UUID.randomUUID())));
        assertThrows(NullPointerException.class, () -> new WarehouseScope(null, Set.of()));
        assertThrows(NullPointerException.class, () -> WarehouseScope.custom(null));
        var invalid = new HashSet<UUID>(); invalid.add(null);
        assertThrows(NullPointerException.class, () -> WarehouseScope.custom(invalid));
    }

    /** 技术容量上限完整接受，超限整体拒绝而不是截断导致部分授权。 */
    @Test
    void acceptsExactBoundAndRejectsOversizedScope() {
        var maximum = IntStream.range(0, WarehouseScope.MAX_WAREHOUSES).mapToObj(index -> new UUID(1, index)).collect(Collectors.toSet());
        var scope = WarehouseScope.custom(maximum); assertEquals(1024, scope.warehouseIds().size());
        assertTrue(scope.permits(new UUID(1, 1023)));
        maximum.add(new UUID(1, 1024));
        assertThrows(IllegalArgumentException.class, () -> WarehouseScope.custom(maximum));
        assertFalse(scope.permits(new UUID(1, 1024)));
    }
}
