package com.patrick.stratum.query.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.spec.QuerySpec;
import org.springframework.util.MultiValueMap;

/** 将明确声明的HTTP查询参数转换为QuerySpec输入，只在输入构造阶段映射400。 */
public final class QueryHttpParameters {
    private final MultiValueMap<String, String> parameters;
    private final Set<String> allowed = new HashSet<>(Set.of("page", "pageSize", "sort"));
    private final List<QuerySpec.FilterInput> filters = new ArrayList<>();

    /**
     * 检查参数数量、重复值与文本长度，保留重复sort而不按逗号拆分。
     * @param parameters 原始查询参数，仅在当前请求构造期间使用
     * @throws IllegalArgumentException 输入超界或非排序参数重复
     * @apiNote 示例：new QueryHttpParameters(parameters).exact("id", UUID.class)。
     */
    public QueryHttpParameters(MultiValueMap<String, String> parameters) {
        if (parameters.size() > QuerySpec.MAX_FILTERS + 3) throw new IllegalArgumentException("查询参数过多");
        parameters.forEach((name, values) -> {
            if (name.length() > 64 || values.isEmpty() || values.size() > (name.equals("sort") ? QuerySpec.MAX_SORTS : 1)) {
                throw new IllegalArgumentException("查询参数重复或超界");
            }
            for (String value : values) {
                if (value == null || value.length() > QuerySpec.MAX_TEXT_LENGTH) throw new IllegalArgumentException("查询文本超界");
            }
        });
        this.parameters = parameters;
    }

    /**
     * 声明精确匹配字段，转换只发生在服务器指定类型上。
     * @param field 字段及HTTP参数名
     * @param type String、UUID、BigDecimal或Instant
     * @return 当前构造器
     * @apiNote 示例：parameters.exact("code", String.class)。
     */
    public QueryHttpParameters exact(String field, Class<?> type) {
        allowed.add(field);
        String value = parameters.getFirst(field);
        if (value != null) filters.add(new QuerySpec.FilterInput(field, QuerySpec.Operator.EQ, scalar(value, type), null));
        return this;
    }

    /**
     * 声明字面包含过滤，HTTP参数使用字段名加Contains。
     * @param field 文本字段
     * @return 当前构造器
     * @apiNote 示例：parameters.contains("name")接收nameContains。
     */
    public QueryHttpParameters contains(String field) {
        String parameter = field + "Contains"; allowed.add(parameter);
        String value = parameters.getFirst(parameter);
        if (value != null) filters.add(new QuerySpec.FilterInput(field, QuerySpec.Operator.CONTAINS, value, null));
        return this;
    }

    /**
     * 声明闭区间，From和To必须同时提供，顺序及存储边界由QuerySpec检查。
     * @param field 字段名
     * @param type 服务端标量类型
     * @return 当前构造器
     * @apiNote 示例：parameters.range("quantity", BigDecimal.class)接收quantityFrom/quantityTo。
     */
    public QueryHttpParameters range(String field, Class<?> type) {
        String from = field + "From"; String to = field + "To";
        allowed.add(from); allowed.add(to);
        String lower = parameters.getFirst(from); String upper = parameters.getFirst(to);
        if ((lower == null) != (upper == null)) throw new IllegalArgumentException("闭区间需要完整上下界");
        if (lower != null) filters.add(new QuerySpec.FilterInput(field, QuerySpec.Operator.BETWEEN, scalar(lower, type), scalar(upper, type)));
        return this;
    }

    /**
     * 拒绝未声明参数并提供分页和排序输入，最终字段规则由QuerySpec验证。
     * @return 解析结果
     * @throws IllegalArgumentException 参数未知或分页格式错误
     * @apiNote 示例：var parsed = parameters.finish()。
     */
    public Parsed finish() {
        if (!allowed.containsAll(parameters.keySet())) throw new IllegalArgumentException("未知查询参数");
        int page = integer("page", 1); int pageSize = integer("pageSize", 20);
        if (page < 1 || pageSize < 1 || pageSize > 200) throw new IllegalArgumentException("分页超出范围");
        return new Parsed(page, pageSize, parameters.getOrDefault("sort", List.of()), filters);
    }

    /**
     * 仅包装请求对象的构造过程，不得在此回调内执行数据库或QueryGateway。
     * @param <T> 输入对象类型
     * @param factory 输入构造函数
     * @return 验证后的输入
     * @throws StratumException 已知输入格式错误时返回40000
     * @apiNote 示例：QueryHttpParameters.input(() -> MasterDataQuery.create(...))。
     */
    public static <T> T input(Supplier<T> factory) {
        try { return factory.get(); }
        catch (IllegalArgumentException invalidInput) { throw new StratumException(CommonErrorCode.BAD_REQUEST); }
    }

    /**
     * 从HTTP过滤器上下文获取可信trace，身份能力仍待Phase D。
     * @return 当前请求匿名查询上下文
     * @apiNote 示例：gateway.execute(query, QueryHttpParameters.context())。
     */
    public static QueryContext context() {
        return QueryContext.anonymous(TraceIdContext.getTraceId().orElseThrow(() -> new IllegalStateException("缺少请求关联上下文")));
    }

    /**
     * 转换白名单标量，UUID要求完整规范形式，避免短格式歧义。
     * @param value 输入文本
     * @param type 服务端类型
     * @return 类型化参数
     */
    private Object scalar(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == UUID.class) {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("UUID格式非法");
            return id;
        }
        if (type == BigDecimal.class) return new BigDecimal(value);
        if (type == Instant.class) {
            try { return Instant.parse(value); }
            catch (DateTimeParseException invalidTime) { throw new IllegalArgumentException("时间格式非法", invalidTime); }
        }
        throw new IllegalStateException("未配置查询参数转换器");
    }

    /**
     * 解析显式分页整数，缺省时使用既定值。
     * @param name 参数名
     * @param fallback 默认值
     * @return 整数
     */
    private int integer(String name, int fallback) {
        String value = parameters.getFirst(name); return value == null ? fallback : Integer.parseInt(value);
    }

    /**
     * 不可变协议输入。
     * @param page 页码
     * @param pageSize 每页数量
     * @param sorts 原始排序表达
     * @param filters 类型化过滤
     */
    public record Parsed(int page, int pageSize, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        /**
         * 冻结输入集合。
         * @param page 页码
         * @param pageSize 每页数量
         * @param sorts 排序
         * @param filters 过滤
         * @apiNote 示例：new Parsed(1, 20, List.of(), List.of())。
         */
        public Parsed { sorts = List.copyOf(sorts); filters = List.copyOf(filters); }
    }
}
