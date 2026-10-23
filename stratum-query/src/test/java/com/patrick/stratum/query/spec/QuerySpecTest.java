package com.patrick.stratum.query.spec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.query.spec.QuerySpec.Field;
import com.patrick.stratum.query.spec.QuerySpec.FilterInput;
import com.patrick.stratum.query.spec.QuerySpec.Operator;

import static org.junit.jupiter.api.Assertions.*;

/** 验证查询参数边界和白名单，SQL绑定另由真实数据库查询测试验证。 */
class QuerySpecTest {
    /** 测试服务器拥有的字段映射，不来自请求参数。 */
    private enum Fields { NAME, AMOUNT, SECRET, TIME, DATE }
    private static final Map<String, Field<Fields>> FIELDS = Map.of(
            "name", new Field<>(Fields.NAME, String.class, Set.of(Operator.EQ, Operator.CONTAINS), true),
            "amount", new Field<>(Fields.AMOUNT, BigDecimal.class, Set.of(Operator.EQ, Operator.BETWEEN), true),
            "secret", new Field<>(Fields.SECRET, String.class, Set.of(), false),
            "time", new Field<>(Fields.TIME, Instant.class, Set.of(Operator.EQ, Operator.BETWEEN), true),
            "date", new Field<>(Fields.DATE, LocalDate.class, Set.of(Operator.EQ, Operator.BETWEEN), true));

    /** 默认分页与上限复用 common，排序映射为服务器字段枚举。 */
    @Test
    void normalizesPagingAndResolvesSorts() {
        var defaults = QuerySpec.create(0, 0, List.of("name,desc"), List.of(), FIELDS);
        assertEquals(1, defaults.getPageRequest().getPage());
        assertEquals(20, defaults.getPageRequest().getPageSize());
        assertEquals(new QuerySpec.Sort<>(Fields.NAME, SortDirection.DESC), defaults.getSorts().getFirst());
        assertEquals(200, QuerySpec.create(1, 1000, List.of(), List.of(), FIELDS).getPageRequest().getPageSize());
    }

    /** 字段注入、非法方向、禁止排序和重复排序都被拒绝。 */
    @Test
    void rejectsUnapprovedSorts() {
        for (String sort : List.of("name;drop table,asc", "secret,asc", "name,random", "name", "name,asc,desc")) {
            assertThrows(IllegalArgumentException.class, () -> QuerySpec.create(1, 20, List.of(sort), List.of(), FIELDS));
        }
        assertThrows(IllegalArgumentException.class,
                () -> QuerySpec.create(1, 20, List.of("name,asc", "name,desc"), List.of(), FIELDS));
    }

    /** 精确条件、包含文本和数值区间保持原参数，不把文本解释为 SQL。 */
    @Test
    void preservesTypedParameters() {
        String injection = "' OR 1=1 -- %_";
        var spec = QuerySpec.create(1, 20, List.of(), List.of(
                new FilterInput("name", Operator.EQ, injection, null),
                new FilterInput("name", Operator.CONTAINS, "%literal_", null),
                new FilterInput("amount", Operator.BETWEEN, new BigDecimal("1.25"), new BigDecimal("8.50"))), FIELDS);
        assertEquals(injection, spec.getFilters().getFirst().value());
        assertEquals(new BigDecimal("8.50"), spec.getFilters().getLast().upper());
        assertEquals(Fields.AMOUNT, spec.getFilters().getLast().field());
    }

    /** 禁止未授权过滤、类型不符、反向范围、额外上限和空模糊查询。 */
    @Test
    void rejectsInvalidFilterTypesAndRanges() {
        var invalid = List.of(
                new FilterInput("unknown", Operator.EQ, "x", null),
                new FilterInput("secret", Operator.EQ, "x", null),
                new FilterInput("amount", Operator.CONTAINS, "1", null),
                new FilterInput("amount", Operator.EQ, "1.0", null),
                new FilterInput("amount", Operator.BETWEEN, BigDecimal.TEN, BigDecimal.ONE),
                new FilterInput("name", Operator.EQ, "x", "y"),
                new FilterInput("name", Operator.CONTAINS, " ", null));
        for (var filter : invalid) {
            assertThrows(IllegalArgumentException.class, () -> QuerySpec.create(1, 20, List.of(), List.of(filter), FIELDS));
        }
    }

    /** 条件数量和文本长度有界，结果不会随输入集合后续变更而变化。 */
    @Test
    void boundsAndFreezesInput() {
        var inputs = new ArrayList<FilterInput>();
        inputs.add(new FilterInput("name", Operator.EQ, "原值", null));
        var spec = QuerySpec.create(1, 20, List.of(), inputs, FIELDS);
        inputs.clear();
        assertEquals(1, spec.getFilters().size());
        assertThrows(UnsupportedOperationException.class, () -> spec.getFilters().clear());
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.create(1, 20, List.of(),
                List.of(new FilterInput("name", Operator.EQ, "x".repeat(513), null)), FIELDS));
        assertThrows(IllegalArgumentException.class, () -> QuerySpec.create(1, 20, List.of(),
                java.util.Collections.nCopies(21, new FilterInput("name", Operator.EQ, "x", null)), FIELDS));
    }

    /** 短指数不能触发无界十进制展开，正负指数、整数溢出边界和区间上界统一提前拒绝。 */
    @Test
    void boundsDecimalExpansionWithoutAllocatingPlainText() {
        for (var valid : List.of(new BigDecimal("1e511"), new BigDecimal("-1e511"), new BigDecimal("1e-511"), new BigDecimal("99999999999999999999999999.999999999999"))) {
            var spec = QuerySpec.create(1, 20, List.of(), List.of(new FilterInput("amount", Operator.EQ, valid, null)), FIELDS);
            assertSame(valid, spec.getFilters().getFirst().value());
        }
        for (var invalid : List.of(new BigDecimal("1e512"), new BigDecimal("1e-512"), new BigDecimal("-1e10000"),
                new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE), new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE),
                new BigDecimal(BigInteger.ZERO, Integer.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class, () -> QuerySpec.create(1, 20, List.of(), List.of(new FilterInput("amount", Operator.EQ, invalid, null)), FIELDS));
            assertThrows(IllegalArgumentException.class, () -> QuerySpec.create(1, 20, List.of(), List.of(new FilterInput("amount", Operator.BETWEEN, BigDecimal.ZERO, invalid)), FIELDS));
        }
    }

    /** 时间参数与DATETIME(6)上下界和微秒精度对齐，不把纳秒条件静默截断。 */
    @Test
    void boundsInstantRangeAndPrecision() {
        Instant lower = Instant.parse("1000-01-01T00:00:00Z"); Instant upper = Instant.parse("9999-12-31T23:59:59.999999Z");
        assertDoesNotThrow(() -> QuerySpec.create(1, 20, List.of(), List.of(new FilterInput("time", Operator.BETWEEN, lower, upper)), FIELDS));
        for (var invalid : List.of(Instant.MIN, Instant.MAX, lower.minusNanos(1000), upper.plusNanos(1000), Instant.parse("2026-10-24T00:00:00.000000001Z"))) {
            assertThrows(IllegalArgumentException.class, () -> QuerySpec.create(1, 20, List.of(), List.of(new FilterInput("time", Operator.EQ, invalid, null)), FIELDS));
            assertThrows(IllegalArgumentException.class, () -> QuerySpec.create(1, 20, List.of(), List.of(new FilterInput("time", Operator.BETWEEN, lower, invalid)), FIELDS));
        }
    }

    /** 日期参数采用与数据库一致的公历范围，边界以外值在比较或绑定前拒绝。 */
    @Test
    void boundsDateRange() {
        assertDoesNotThrow(() -> QuerySpec.create(1, 20, List.of(), List.of(new FilterInput("date", Operator.BETWEEN,
                LocalDate.of(1000, 1, 1), LocalDate.of(9999, 12, 31))), FIELDS));
        for (var invalid : List.of(LocalDate.MIN, LocalDate.MAX, LocalDate.of(999, 12, 31), LocalDate.of(10000, 1, 1))) {
            assertThrows(IllegalArgumentException.class, () -> QuerySpec.create(1, 20, List.of(), List.of(new FilterInput("date", Operator.EQ, invalid, null)), FIELDS));
        }
    }
}
