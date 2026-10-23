package com.patrick.stratum.domain.idempotency;

import java.util.Optional;

/** 幂等回执仓储端口：通过数据库唯一约束与业务事务共同保护重复写入。 */
public interface IdempotencyRepository {
    /**
     * 按完整作用域读取已提交回执，不使用跨请求内存状态。
     * @param key 完整幂等键
     * @return 已有回执或空结果
     * @apiNote 示例：repository.find(key)。
     */
    Optional<IdempotencyReceipt> find(IdempotencyReceipt.Key key);

    /**
     * 在当前工作单元中插入完整回执，禁止覆盖相同键的结果。
     * @param receipt 非空的完成回执
     * @throws IllegalStateException 不在工作单元中调用
     * @throws com.patrick.stratum.common.error.StratumException 相同键已存在，调用方必须回滚并重试
     * @apiNote 示例：在unitOfWork.execute回调中调用repository.append(receipt)。
     */
    void append(IdempotencyReceipt receipt);
}
