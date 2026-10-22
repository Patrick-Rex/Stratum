package com.patrick.stratum.application.transaction;

import java.util.function.Supplier;

/**
 * Application 层工作单元门面，统一执行写入、提交与失败回滚边界。
 * 事务适配器应使业务持久化与 Outbox 写入共享同一事务。
 */
public interface UnitOfWork {

    /**
     * 在一个工作单元内执行操作；异常必须向外传播，禁止提交部分结果。
     *
     * @param <T> 操作返回值类型
     * @param action 非空操作，在事务内调用且仅调用一次
     * @return 操作结果
     * @throws RuntimeException 操作或提交失败，事务适配器负责回滚
     * @apiNote 示例：{@code unitOfWork.execute(() -> { repository.save(note); return null; })}。
     */
    <T> T execute(Supplier<T> action);
}
