package com.patrick.stratum.query.spec;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.common.base.request.BasePageRequest;
import com.patrick.stratum.common.enums.SortDirection;

import lombok.Getter;

/**
 * 仅通过服务端白名单生成的不可变查询表达，不包含 SQL 或 ORM 可变对象。
 * @param <F> 服务端定义的查询字段枚举
 */
@Getter
public final class QuerySpec<F extends Enum<F>> {
    public static final int MAX_SORTS = 3;
    public static final int MAX_FILTERS = 20;
    public static final int MAX_TEXT_LENGTH = 512;
    public static final int MAX_DECIMAL_DIGITS = 512;
    private static final Instant MIN_INSTANT = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final Set<Class<?>> VALUE_TYPES = Set.of(String.class, Integer.class, Long.class,
            BigDecimal.class, LocalDate.class, Instant.class, Boolean.class, UUID.class);
    private final BasePageRequest pageRequest;
    private final List<Sort<F>> sorts;
    private final List<Filter<F>> filters;

    /**
     * 保存已校验的条件，私有构造防止绕过白名单。
     * @param pageRequest 已归一化分页
     * @param sorts 类型化排序
     * @param filters 类型化过滤
     */
    private QuerySpec(BasePageRequest pageRequest, List<Sort<F>> sorts, List<Filter<F>> filters) {
        this.pageRequest = pageRequest;
        this.sorts = List.copyOf(sorts);
        this.filters = List.copyOf(filters);
    }

    /**
     * 将外部字段名映射为服务器枚举，拒绝未知字段、非法操作及类型不符的参数。
     * @param <F> 字段枚举类型
     * @param page 原页码，复用 common 归一化
     * @param pageSize 原分页大小，默认 20、最大 200
     * @param sorts field,asc|desc 格式的排序，最多 3 项
     * @param filters 过滤条件，最多 20 项
     * @param fields 服务端白名单，不能由请求方提供
     * @return 可安全翻译为 ORM 参数绑定的规范
     * @throws IllegalArgumentException 排序、过滤或值不符合白名单时抛出
     * @apiNote 示例：QuerySpec.create(1, 20, List.of("name,asc"), List.of(), serverFields)。
     */
    public static <F extends Enum<F>> QuerySpec<F> create(int page, int pageSize, List<String> sorts,
            List<FilterInput> filters, Map<String, Field<F>> fields) {
        Objects.requireNonNull(sorts, "sorts");
        Objects.requireNonNull(filters, "filters");
        var whitelist = Map.copyOf(fields);
        if (sorts.size() > MAX_SORTS || filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("查询条件数量超过上限");
        }
        var parsedSorts = new ArrayList<Sort<F>>(sorts.size());
        var sortedFields = new HashSet<F>();
        for (String sort : sorts) {
            String[] tokens = Objects.requireNonNull(sort, "sort").split(",", -1);
            if (tokens.length != 2 || !(tokens[1].equalsIgnoreCase("asc") || tokens[1].equalsIgnoreCase("desc"))) {
                throw new IllegalArgumentException("排序必须使用 field,asc|desc 格式");
            }
            var field = allowed(whitelist, tokens[0]);
            if (!field.sortable() || !sortedFields.add(field.target())) {
                throw new IllegalArgumentException("排序字段不允许或重复");
            }
            parsedSorts.add(new Sort<>(field.target(), SortDirection.fromToken(tokens[1])));
        }
        var parsedFilters = new ArrayList<Filter<F>>(filters.size());
        for (var input : filters) {
            Objects.requireNonNull(input, "filter");
            var field = allowed(whitelist, input.field());
            if (input.operator() == null || !field.operators().contains(input.operator())) {
                throw new IllegalArgumentException("过滤操作未获允许");
            }
            validateValue(input.value(), field.valueType());
            if (input.operator() == Operator.BETWEEN) {
                validateValue(input.upper(), field.valueType());
                if (compare(input.value(), input.upper()) > 0) { throw new IllegalArgumentException("区间下限不能大于上限"); }
            } else if (input.upper() != null) { throw new IllegalArgumentException("仅区间操作接受上限参数"); }
            if (input.operator() == Operator.CONTAINS && ((String) input.value()).isBlank()) {
                throw new IllegalArgumentException("模糊查询文本不能为空");
            }
            parsedFilters.add(new Filter<>(field.target(), input.operator(), input.value(), input.upper()));
        }
        return new QuerySpec<>(new BasePageRequest(page, pageSize, SortDirection.ASC), parsedSorts, parsedFilters);
    }

    /**
     * 将外部字段名限制在服务端白名单中。
     * @param <F> 字段枚举
     * @param fields 白名单
     * @param name 外部字段名
     * @return 服务器字段定义
     */
    private static <F extends Enum<F>> Field<F> allowed(Map<String, Field<F>> fields, String name) {
        var field = name == null ? null : fields.get(name);
        if (field == null) { throw new IllegalArgumentException("查询字段不在白名单中"); }
        return field;
    }

    /**
     * 校验不可变标量和资源边界；数值展开及时间精度在参数进入数据库前检查，不分配展开字符串。
     * @param value 参数值
     * @param type 服务端允许类型
     */
    private static void validateValue(Object value, Class<?> type) {
        if (value == null || value.getClass() != type
                || value instanceof String text && text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("查询参数类型错误或长度超过上限");
        }
        if (value instanceof BigDecimal decimal) {
            long integerDigits = Math.max(1L, (long) decimal.precision() - decimal.scale());
            long fractionDigits = Math.max(0L, (long) decimal.scale());
            if (integerDigits + fractionDigits > MAX_DECIMAL_DIGITS) throw new IllegalArgumentException("查询数值展开位数超过上限");
        }
        if (value instanceof Instant instant && (instant.isBefore(MIN_INSTANT) || instant.isAfter(MAX_INSTANT) || instant.getNano() % 1_000 != 0)) {
            throw new IllegalArgumentException("查询时间超出数据库范围或微秒精度");
        }
        if (value instanceof LocalDate date && (date.getYear() < 1000 || date.getYear() > 9999)) {
            throw new IllegalArgumentException("查询日期超出数据库范围");
        }
    }

    /**
     * 比较已经验证为同种不可变标量的区间端点。
     * @param lower 下界
     * @param upper 上界
     * @return 比较结果
     */
    @SuppressWarnings("unchecked")
    private static int compare(Object lower, Object upper) { return ((Comparable<Object>) lower).compareTo(upper); }

    /** 支持的条件类型，CONTAINS 由适配器转换为字面文本包含而非 SQL 片段。 */
    public enum Operator { EQ, BETWEEN, CONTAINS }

    /**
     * 服务端字段定义，不允许请求方提交 ORM 列名。
     * @param <F> 字段枚举
     * @param target 字段标识
     * @param valueType 不可变标量类型
     * @param operators 此字段允许的操作
     * @param sortable 是否允许排序
     */
    public record Field<F extends Enum<F>>(F target, Class<?> valueType, Set<Operator> operators, boolean sortable) {
        /**
         * 校验服务器配置并冻结操作集合。
         * @param target 字段标识
         * @param valueType 参数类型
         * @param operators 允许操作
         * @param sortable 是否可排序
         * @apiNote 示例：new Field<>(Fields.NAME, String.class, Set.of(Operator.CONTAINS), true)。
         */
        public Field {
            Objects.requireNonNull(target, "target");
            if (valueType == null || !VALUE_TYPES.contains(valueType)) { throw new IllegalArgumentException("不支持的查询参数类型"); }
            operators = Set.copyOf(operators);
            if (operators.contains(Operator.CONTAINS) && valueType != String.class) {
                throw new IllegalArgumentException("模糊查询只适用于文本字段");
            }
        }
    }

    /**
     * 外部过滤输入，创建 QuerySpec 时才赋予已校验语义。
     * @param field 外部字段名
     * @param operator 过滤操作
     * @param value 精确值、包含文本或区间下限
     * @param upper 区间上限，其他操作必须为 null
     */
    public record FilterInput(String field, Operator operator, Object value, Object upper) { }

    /**
     * 已解析的排序项，适配器按枚举分派到 ORM 列。
     * @param <F> 字段枚举
     * @param field 服务端字段
     * @param direction 排序方向
     */
    public record Sort<F extends Enum<F>>(F field, SortDirection direction) { }

    /**
     * 已解析的参数化过滤项，值不经过字符串拼接。
     * @param <F> 字段枚举
     * @param field 服务端字段
     * @param operator 允许的操作
     * @param value 参数值或下限
     * @param upper 可空上限
     */
    public record Filter<F extends Enum<F>>(F field, Operator operator, Object value, Object upper) { }
}
