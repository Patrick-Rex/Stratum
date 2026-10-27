package com.patrick.stratum.infrastructure.outbox;

import java.sql.SQLException;
import java.util.List;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxRelayControlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在共享数据库单例行上协调Relay暂停版本与批次准入，不自行开启事务。 */
@Repository
@RequiredArgsConstructor
public class OutboxRelayControlRepositoryImpl implements OutboxRelayControlRepository {
    private final JdbcTemplate jdbc;

    /** {@inheritDoc} */
    @Override
    public State current() { return load(false); }

    /** {@inheritDoc} */
    @Override
    public State change(long expectedRevision, boolean paused) {
        requireWrite();
        if (expectedRevision < 0) throw new IllegalArgumentException("Relay控制版本不能为负数");
        var state = load(true);
        if (state.revision() != expectedRevision) throw conflict("Relay控制版本已变更");
        if (state.paused() == paused) return state;
        if (state.revision() == Long.MAX_VALUE) throw conflict("Relay控制版本已达到上限");
        try {
            if (jdbc.update("UPDATE outbox_relay_control SET paused=?, revision=revision+1 WHERE singleton_id=1 AND revision=?",
                    paused, expectedRevision) != 1) throw conflict("Relay控制状态已变更");
            return new State(paused, expectedRevision + 1);
        } catch (DataAccessException failure) { throw translate(failure); }
    }

    /** {@inheritDoc} */
    @Override
    public Admission tryAdmit() {
        requireWrite();
        var state = load(true);
        return new Admission(!state.paused(), state.revision());
    }

    /**
     * 读取状态；变更与准入必须获取同一单例行的当前读锁，锁忙时失败关闭。
     * @param lock 是否持有当前工作单元行锁
     * @return 唯一控制状态
     */
    private State load(boolean lock) {
        try {
            var states = jdbc.query("SELECT paused,revision FROM outbox_relay_control WHERE singleton_id=1" + (lock ? " FOR UPDATE NOWAIT" : ""),
                    (row, number) -> new State(row.getBoolean(1), row.getLong(2)));
            if (states.size() != 1) throw new IllegalStateException("Relay控制单例不存在");
            return states.getFirst();
        } catch (DataAccessException failure) { throw translate(failure); }
    }

    /** 拒绝在Application写工作单元外修改或准入。 */
    private static void requireWrite() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Relay控制必须参与已有写工作单元");
        }
    }

    /**
     * 将锁竞争和序列化冲突交给调用方在新工作单元中处理。
     * @param failure 持久化异常
     * @return 项目冲突异常或原始异常
     */
    private static RuntimeException translate(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1020 || sql.getErrorCode() == 1205 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of("Relay控制状态正在变更"), failure);
            }
        }
        return failure;
    }

    /**
     * 构造不包含敏感信息的控制状态冲突。
     * @param message 固定错误描述
     * @return 项目冲突异常
     */
    private static StratumException conflict(String message) { return new StratumException(CommonErrorCode.CONFLICT, message); }
}
