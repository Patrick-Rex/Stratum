package com.patrick.stratum.query;

import com.patrick.stratum.query.dto.QueryResult;

/**
 * 具体只读查询处理器；调用方身份由服务器显式传入，不从查询输入中采信。
 * @param <Q> 请求类型
 * @param <R> 单条读模型类型
 */
public interface QueryHandler<Q extends Query<R>, R> {
    /**
     * 提供唯一、稳定的查询标识，供观测和路由注册校验。
     * @return 小写查询标识
     * @apiNote 示例：erp.material.list。
     */
    String queryCode();

    /**
     * 提供精确的请求类型，不根据客户端字符串反射加载类型。
     * @return 请求类
     * @apiNote 示例：ListMaterialsQuery.class。
     */
    Class<Q> queryType();

    /**
     * 执行只读查询，权限条件和脱敏在具体读取实现中应用。
     * @param query 已校验请求
     * @param context 可信调用上下文
     * @return 非空分页结果
     * @apiNote 由 QueryGateway 调用 handler.handle(query, context)，处理器禁止执行写入。
     */
    QueryResult<R> handle(Q query, QueryContext context);
}
