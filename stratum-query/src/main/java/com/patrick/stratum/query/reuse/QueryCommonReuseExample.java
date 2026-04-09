package com.patrick.stratum.query.reuse;

import java.util.List;

import com.patrick.stratum.common.base.BasePageRequest;
import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.common.extension.CollectionExtensions;
import com.patrick.stratum.common.helper.ValidationHelper;

/**
 * Query 层 common 复用示例，负责展示分页、排序、集合与校验能力的统一复用。
 * 适用场景：查询入口参数标准化与查询字段白名单校验。
 */
public final class QueryCommonReuseExample {

    private QueryCommonReuseExample() {
    }

    /**
     * 构建查询请求对象并收敛分页与排序参数。
     *
     * @param page 原始页码。
     * @param pageSize 原始分页大小。
     * @param sortDirectionToken 排序方向文本，支持 asc/desc。
     * @return 归一化后的分页请求对象。
     * @throws 无。
     */
    public static BasePageRequest toPageRequest(int page, int pageSize, String sortDirectionToken) {
        return new BasePageRequest(page, pageSize, SortDirection.fromToken(sortDirectionToken));
    }

    /**
     * 校验查询字段白名单是否可用。
     *
     * @param allowedSortFields 允许排序字段集合。
     * @return 白名单字段的不可变副本。
     * @throws IllegalArgumentException 当字段集合为空时抛出异常。
     */
    public static List<String> validateSortWhitelist(List<String> allowedSortFields) {
        ValidationHelper.requireNonNull(allowedSortFields, "allowedSortFields");
        if (CollectionExtensions.isEmpty(allowedSortFields)) {
            throw new IllegalArgumentException("allowedSortFields cannot be empty");
        }
        return CollectionExtensions.immutableCopy(allowedSortFields);
    }
}
