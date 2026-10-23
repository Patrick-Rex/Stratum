package com.patrick.stratum.starter.integration;

import java.util.UUID;

import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.idempotency.IdempotencyReceipt;
import com.patrick.stratum.domain.idempotency.IdempotencyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MariaDB验证回执持久化、唯一键与工作单元回滚，不模拟存储。 */
class IdempotencyRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final String scope = "test:" + UUID.randomUUID();
    private final IdempotencyRepository repository;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;

    /**
     * 装配实际仓储及事务入口。
     * @param repository 回执仓储
     * @param unitOfWork Application工作单元
     * @param jdbc 独立断言与清理客户端
     */
    @Autowired
    IdempotencyRepositoryIntegrationTest(IdempotencyRepository repository, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.repository = repository;
        this.unitOfWork = unitOfWork;
        this.jdbc = jdbc;
    }

    /** 复合键保持大小写与作用域隔离，JSON业务结果可完整读取。 */
    @Test
    void persistsCaseSensitiveScopedResults() {
        var first = receipt(scope, "Key", "{\"id\":\"中文结果\"}");
        var second = receipt(scope, "key", "{\"id\":2}");
        var third = receipt(scope + ":other", "Key", "{\"id\":3}");
        unitOfWork.execute(() -> {
            repository.append(first); repository.append(second); repository.append(third); return null;
        });
        assertEquals(first, repository.find(first.key()).orElseThrow());
        assertEquals(second, repository.find(second.key()).orElseThrow());
        assertEquals(third, repository.find(third.key()).orElseThrow());
        assertTrue(repository.find(new IdempotencyReceipt.Key(scope, "missing")).isEmpty());
    }

    /** 重复键不覆盖原结果，重复请求中的其他写入也会回滚。 */
    @Test
    void duplicateReceiptRollsBackTheWholeWorkUnit() {
        var original = receipt(scope, "same", "{\"id\":1}");
        unitOfWork.execute(() -> { repository.append(original); return null; });
        var extra = receipt(scope, "new", "{\"id\":2}");
        var conflict = assertThrows(StratumException.class, () -> unitOfWork.execute(() -> {
            repository.append(extra);
            repository.append(receipt(scope, "same", "{\"id\":3}"));
            return null;
        }));
        assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode());
        assertEquals(original, repository.find(original.key()).orElseThrow());
        assertTrue(repository.find(extra.key()).isEmpty());
    }

    /** 回执插入后的异常撤销回执，允许原键重新执行。 */
    @Test
    void rollsBackReceiptAfterFailure() {
        var receipt = receipt(scope, "rollback", "{}");
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            repository.append(receipt); throw new IllegalStateException("测试回滚");
        }));
        assertTrue(repository.find(receipt.key()).isEmpty());
        unitOfWork.execute(() -> { repository.append(receipt); return null; });
        assertEquals(receipt, repository.find(receipt.key()).orElseThrow());
    }

    /** 事务外不能单独提交回执，非法JSON由数据库拒绝且回滚整笔事务。 */
    @Test
    void rejectsWritesOutsideTransactionAndInvalidJson() {
        var valid = receipt(scope, "valid", "{}");
        assertThrows(IllegalStateException.class, () -> repository.append(valid));
        assertThrows(EasyQueryException.class, () -> unitOfWork.execute(() -> {
            repository.append(valid);
            repository.append(receipt(scope, "invalid", "not-json"));
            return null;
        }));
        assertTrue(repository.find(valid.key()).isEmpty());
    }

    /** 键及结果边界在进入ORM之前校验，包括多字节UTF-8结果。 */
    @Test
    void rejectsMalformedOrUnboundedReceipts() {
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyReceipt.Key(scope, " "));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyReceipt.Key(scope, "x".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyReceipt.Key("x".repeat(201), "key"));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyReceipt.Key(scope, "' OR 1=1"));
        assertThrows(IllegalArgumentException.class,
                () -> new IdempotencyReceipt(new IdempotencyReceipt.Key(scope, "key"), "invalid", "{}"));
        assertThrows(IllegalArgumentException.class, () -> receipt(scope, "key", "中".repeat(90_000)));
    }

    /** 仅删除测试拥有的两个作用域，不清理其他回执。 */
    @AfterEach
    void cleanOwnRows() {
        jdbc.update("DELETE FROM idempotency_receipt WHERE scope IN (?, ?)", scope, scope + ":other");
    }

    /**
     * 构造边界内的测试回执。
     * @param scope 测试作用域
     * @param key 测试请求键
     * @param result 结果JSON
     * @return 完成回执
     */
    private IdempotencyReceipt receipt(String scope, String key, String result) {
        return new IdempotencyReceipt(new IdempotencyReceipt.Key(scope, key), "a".repeat(64), result);
    }
}
