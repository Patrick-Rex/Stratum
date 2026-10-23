package com.patrick.stratum.query;

import com.patrick.stratum.query.spec.QuerySpec;

/**
 * 类型化只读请求，输入 DTO 持有已通过服务端白名单解析的查询规范。
 * @param <R> 单条读模型类型
 */
public interface Query<R> {
    /**
     * 返回本次分页及条件表达，不共享可变 ORM 查询对象。
     * @return 非空查询规范
     * @apiNote 请求记录可声明 spec 分量并实现 Query<Row>，由生成的访问器满足此契约。
     */
    QuerySpec<?> spec();
}
