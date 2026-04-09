package com.patrick.stratum.common.helper;

/**
 * 分页帮助类，负责统一 page/pageSize 的默认值与边界收敛规则。
 * 适用场景：QueryGateway 与各查询入口对分页参数的标准化处理。
 */
public final class PageHelper {

    /**
     * 默认页码（从 1 开始）。
     */
    public static final int DEFAULT_PAGE = 1;

    /**
     * 默认分页大小。
     */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 最大分页大小。
     */
    public static final int MAX_PAGE_SIZE = 200;

    private PageHelper() {
    }

    /**
     * 归一化页码，小于 1 时回退到默认页码。
     *
     * @param page 原始页码。
     * @return 合法页码，最小值为 1。
     * @throws 无。
     */
    public static int normalizePage(int page) {
        return page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
    }

    /**
     * 归一化分页大小，小于等于 0 时回退默认值，超过上限时截断到上限。
     *
     * @param pageSize 原始分页大小。
     * @return 合法分页大小，范围为 [1, 200]。
     * @throws 无。
     */
    public static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
