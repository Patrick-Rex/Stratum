package com.patrick.stratum.common.base;

import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.common.helper.PageHelper;

/**
 * 基础分页请求对象，负责沉淀跨模块统一的分页参数规范化能力。
 * 适用场景：query/application 的分页查询入参基类。
 */
public class BasePageRequest {

    private final int page;
    private final int pageSize;
    private final SortDirection sortDirection;

    /**
     * 构建基础分页请求并执行参数归一化。
     *
     * @param page 原始页码，小于 1 将回退到默认值 1。
     * @param pageSize 原始分页大小，非法值将自动归一化到允许范围。
     * @param sortDirection 排序方向，允许为 null；为 null 时默认 ASC。
     * @return 无返回值。
     * @throws 无。
     */
    public BasePageRequest(int page, int pageSize, SortDirection sortDirection) {
        this.page = PageHelper.normalizePage(page);
        this.pageSize = PageHelper.normalizePageSize(pageSize);
        this.sortDirection = sortDirection == null ? SortDirection.ASC : sortDirection;
    }

    /**
     * 获取归一化后的页码。
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
     * 获取排序方向。
     *
     * @param 无。
     * @return 排序方向枚举。
     * @throws 无。
     */
    public SortDirection getSortDirection() {
        return sortDirection;
    }
}
