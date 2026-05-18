package com.patrick.stratum.common.base.result;

import java.util.List;

import com.patrick.stratum.common.helper.PageHelper;

import lombok.Builder;

/**
 * 基础分页结果对象，负责沉淀跨模块统一的分页结果结构。
 * 适用场景：query/interface 返回分页数据体时的基础结果模型。
 *
 * @param <T> 分页数据项类型。
 */
public class BasePageResult<T> {

    private static final long MIN_TOTAL = 0L;

    private final int page;
    private final int pageSize;
    private final long total;
    private final List<T> items;

    /**
     * 构建基础分页结果并执行字段归一化。
     *
     * @param page 当前页码，小于 1 将回退到默认值 1。
     * @param pageSize 当前分页大小，非法值将自动归一化到允许范围。
     * @param total 总记录数，小于 0 时回退为 0。
     * @param items 当前页数据项集合，允许为 null；为 null 时回退为空列表。
     * @return 无返回值。
     * @throws 无。
     * @apiNote 使用示例：BasePageResult.<String>builder().page(1).pageSize(20).total(100).items(List.of("A")).build()
     */
    @Builder
    public BasePageResult(int page, int pageSize, long total, List<T> items) {
        this.page = PageHelper.normalizePage(page);
        this.pageSize = PageHelper.normalizePageSize(pageSize);
        this.total = Math.max(total, MIN_TOTAL);
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 获取归一化后的当前页码。
     *
     * @param 无。
     * @return 页码，最小值为 1。
     * @throws 无。
     */
    public int getPage() {
        return page;
    }

    /**
     * 获取归一化后的分页大小。
     *
     * @param 无。
     * @return 分页大小，范围为 [1, 200]。
     * @throws 无。
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * 获取当前查询总记录数。
     *
     * @param 无。
     * @return 总记录数，最小值为 0。
     * @throws 无。
     */
    public long getTotal() {
        return total;
    }

    /**
     * 获取当前页数据项集合。
     *
     * @param 无。
     * @return 不为 null 的不可变数据项集合。
     * @throws 无。
     */
    public List<T> getItems() {
        return items;
    }
}