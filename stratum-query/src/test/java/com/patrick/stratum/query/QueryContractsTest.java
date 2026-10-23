package com.patrick.stratum.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.patrick.stratum.query.dto.QueryResult;

import static org.junit.jupiter.api.Assertions.*;

/** 验证查询结果边界和显式上下文不可变性，不将契约测试等同于权限验证。 */
class QueryContractsTest {
    /** 查询结果复用 common 分页默认值，并保存输入列表快照。 */
    @Test
    void resultNormalizesAndFreezesPage() {
        var rows = new ArrayList<>(List.of("条目"));
        var result = QueryResult.<String>queryBuilder().page(0).pageSize(0).total(1).items(rows).build();
        rows.clear();
        assertEquals(1, result.getPage());
        assertEquals(20, result.getPageSize());
        assertEquals(List.of("条目"), result.getItems());
        assertThrows(UnsupportedOperationException.class, () -> result.getItems().clear());
    }

    /** 负总数、总数小于当前行数和单页超量都不能生成成功响应。 */
    @Test
    void resultRejectsInvalidCounts() {
        assertThrows(IllegalArgumentException.class, () -> new QueryResult<>(1, 20, -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QueryResult<>(1, 20, 0, List.of("x")));
        assertThrows(IllegalArgumentException.class, () -> new QueryResult<>(1, 1, 2, List.of("x", "y")));
        assertThrows(IllegalArgumentException.class, () -> new QueryResult<>(1, 500, 201,
                java.util.Collections.nCopies(201, "x")));
    }

    /** 空页允许保留总数，用于超出末页时稳定返回统一响应。 */
    @Test
    void resultAllowsEmptyPage() {
        var result = new QueryResult<String>(10, 20, 3, List.of());
        assertEquals(3, result.getTotal());
        assertTrue(result.getItems().isEmpty());
    }

    /** 权限集合按快照传递，调用方后续修改不会影响正在执行的查询。 */
    @Test
    void contextFreezesPermissions() {
        var permissions = new HashSet<>(Set.of("erp.material.read"));
        var context = new QueryContext.Snapshot("trace-001", Optional.of("operator"), permissions);
        permissions.clear();
        assertEquals(Set.of("erp.material.read"), context.permissions());
        assertThrows(UnsupportedOperationException.class, () -> context.permissions().clear());
        assertTrue(QueryContext.anonymous("trace-anonymous").operatorId().isEmpty());
        assertTrue(QueryContext.anonymous("trace-anonymous").permissions().isEmpty());
    }

    /** 拒绝可注入日志的 trace、空操作者以及带权限的匿名上下文。 */
    @Test
    void contextRejectsInvalidIdentityMetadata() {
        assertThrows(IllegalArgumentException.class, () -> QueryContext.anonymous("trace\nforged"));
        assertThrows(IllegalArgumentException.class,
                () -> new QueryContext.Snapshot("trace", Optional.of(" "), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new QueryContext.Snapshot("trace", Optional.empty(), Set.of("erp.material.read")));
    }
}
