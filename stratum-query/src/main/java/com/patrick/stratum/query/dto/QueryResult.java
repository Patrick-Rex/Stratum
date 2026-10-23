package com.patrick.stratum.query.dto;

import java.util.List;

import com.patrick.stratum.common.base.result.BasePageResult;
import com.patrick.stratum.common.helper.PageHelper;

import lombok.Builder;

/**
 * 统一查询分页结果，复用 common 的不可变数据列表并拒绝超量或不一致的响应。
 * @param <R> 单条只读输出模型类型
 */
public final class QueryResult<R> extends BasePageResult<R> {
    /**
     * 构建有界查询结果；输入总数不能小于本页数量，条数不能超过归一化 pageSize。
     * @param page 原始页码
     * @param pageSize 原始分页大小
     * @param total 总记录数，非负
     * @param items 当前页结果，必须非空引用，可为空列表
     * @throws IllegalArgumentException 数量不一致或结果超出本页上限时抛出
     * @apiNote 示例：QueryResult.<String>queryBuilder().page(1).pageSize(20).total(1).items(List.of("条目")).build()。
     */
    @Builder(builderMethodName = "queryBuilder")
    public QueryResult(int page, int pageSize, long total, List<R> items) {
        if (items == null || total < 0 || total < items.size() || items.size() > PageHelper.normalizePageSize(pageSize)) {
            throw new IllegalArgumentException("查询结果数量与分页约束不一致");
        }
        super(page, pageSize, total, items);
    }
}
