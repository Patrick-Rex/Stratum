package com.patrick.stratum.domain.job;

import java.util.Optional;

import com.patrick.stratum.domain.event.OutboxReplayRepository.Outcome;
import com.patrick.stratum.domain.job.DeadLetterReplayTaskStatus.Consumption;
import com.patrick.stratum.domain.job.DeadLetterReplayTaskStatus.Delivery;
import com.patrick.stratum.domain.job.DeadLetterReplayTaskStatus.Evidence;
import com.patrick.stratum.domain.job.DeadLetterReplayTaskStatus.Status;
import com.patrick.stratum.domain.job.JobExecutionRepository.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 按单一快照事实验证死信重放任务的公开状态，不执行数据库或消息操作。 */
class DeadLetterReplayTaskStatusTest {
    /** 未登记重放时区分等待、开始、失败和取消；技术成功但缺回执不得宣称成功。 */
    @Test
    void derivesStatusBeforeReplayRegistration() {
        assertStatus(Status.PENDING, 0, evidence(State.WAITING, 0, null, null, false, Consumption.NONE));
        assertStatus(Status.RUNNING, 0, evidence(State.WAITING, 1, null, null, false, Consumption.NONE));
        assertStatus(Status.RUNNING, 0, evidence(State.RUNNING, 1, null, null, false, Consumption.NONE));
        assertStatus(Status.RUNNING, 0, evidence(State.RETRY_WAIT, 1, null, null, false, Consumption.NONE));
        var failed = DeadLetterReplayTaskStatus.evaluate(evidence(State.DEAD, 3, null, null, false, Consumption.NONE));
        assertEquals(Status.FAILED, failed.status()); assertEquals(Optional.of(DeadLetterReplayTaskStatus.Error.EXECUTION_FAILED), failed.lastError());
        assertStatus(Status.CANCELLED, 0, evidence(State.CANCELLED, 0, null, null, false, Consumption.NONE));
        assertStatus(Status.PENDING, 0, evidence(State.WAITING, 0, null, null, false, Consumption.VALID));
        assertThrows(IllegalStateException.class, () -> DeadLetterReplayTaskStatus.evaluate(evidence(State.SUCCEEDED, 1, null, null, false, Consumption.NONE)));
    }

    /** 已登记及已发送仍为进行中，只有已解决且有核验消费凭据才达到100。 */
    @Test
    void requiresVerifiedConsumptionInsteadOfSendOrRegistration() {
        for (var delivery : new Delivery[] {Delivery.PENDING, Delivery.PROCESSING, Delivery.SENT}) {
            assertStatus(Status.RUNNING, 0, evidence(State.SUCCEEDED, 1, Outcome.QUEUED, delivery, true, Consumption.NONE));
            assertStatus(Status.RUNNING, 0, evidence(State.DEAD, 3, Outcome.ALREADY_QUEUED, delivery, true, Consumption.NONE));
        }
        var failed = DeadLetterReplayTaskStatus.evaluate(evidence(State.SUCCEEDED, 1, Outcome.QUEUED, Delivery.FAILED, true, Consumption.NONE));
        assertEquals(Status.FAILED, failed.status()); assertEquals(Optional.of(DeadLetterReplayTaskStatus.Error.REPLAY_FAILED), failed.lastError());
        assertStatus(Status.SUCCEEDED, 100, evidence(State.SUCCEEDED, 1, Outcome.QUEUED, Delivery.RESOLVED, true, Consumption.VALID));
        assertStatus(Status.SUCCEEDED, 100, evidence(State.DEAD, 3, Outcome.ALREADY_CONSUMED, null, true, Consumption.VALID));
    }

    /** 缺失、篡改、目标不符或互相矛盾的成功证据均失败关闭。 */
    @Test
    void rejectsInconsistentEvidence() {
        assertThrows(IllegalStateException.class, () -> DeadLetterReplayTaskStatus.evaluate(evidence(State.SUCCEEDED, 1, Outcome.QUEUED, Delivery.RESOLVED, true, Consumption.NONE)));
        assertThrows(IllegalStateException.class, () -> DeadLetterReplayTaskStatus.evaluate(evidence(State.SUCCEEDED, 1, Outcome.QUEUED, Delivery.SENT, true, Consumption.VALID)));
        assertThrows(IllegalStateException.class, () -> DeadLetterReplayTaskStatus.evaluate(evidence(State.SUCCEEDED, 1, Outcome.ALREADY_CONSUMED, null, true, Consumption.NONE)));
        assertThrows(IllegalStateException.class, () -> DeadLetterReplayTaskStatus.evaluate(evidence(State.SUCCEEDED, 1, Outcome.ALREADY_CONSUMED, null, true, Consumption.INVALID)));
        assertThrows(IllegalStateException.class, () -> DeadLetterReplayTaskStatus.evaluate(evidence(State.SUCCEEDED, 1, Outcome.QUEUED, Delivery.RESOLVED, false, Consumption.VALID)));
        assertThrows(IllegalStateException.class, () -> DeadLetterReplayTaskStatus.evaluate(evidence(State.CANCELLED, 0, Outcome.QUEUED, Delivery.PENDING, true, Consumption.NONE)));
        assertThrows(IllegalStateException.class, () -> DeadLetterReplayTaskStatus.evaluate(evidence(State.SUCCEEDED, 1, Outcome.QUEUED, null, true, Consumption.NONE)));
        assertThrows(IllegalStateException.class, () -> DeadLetterReplayTaskStatus.evaluate(evidence(State.WAITING, 0, null, Delivery.PENDING, false, Consumption.NONE)));
    }

    /** 不接受负尝试数、伪造已开始状态或错误与状态矛盾的输出。 */
    @Test
    void validatesStatusAndProgressBounds() {
        assertThrows(IllegalArgumentException.class, () -> evidence(State.RUNNING, 0, null, null, false, Consumption.NONE));
        assertThrows(IllegalArgumentException.class, () -> evidence(State.CANCELLED, 1, null, null, false, Consumption.NONE));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterReplayTaskStatus.Result(Status.SUCCEEDED, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterReplayTaskStatus.Result(Status.RUNNING, 0, Optional.of(DeadLetterReplayTaskStatus.Error.REPLAY_FAILED)));
    }

    /**
     * 构造单条任务的不可变证据。
     * @param state 技术状态
     * @param attempts 累计尝试
     * @param outcome 可空重放回执
     * @param delivery 可空投递状态
     * @param target 目标是否匹配
     * @param consumption 消费凭据核验
     * @return 测试证据
     */
    private Evidence evidence(State state, long attempts, Outcome outcome, Delivery delivery, boolean target, Consumption consumption) {
        return new Evidence(state, attempts, Optional.ofNullable(outcome), Optional.ofNullable(delivery), target, consumption);
    }

    /**
     * 核对状态和进度，不为未失败状态提供错误分类。
     * @param expected 预期状态
     * @param progress 预期进度
     * @param evidence 输入证据
     */
    private void assertStatus(Status expected, int progress, Evidence evidence) {
        var result = DeadLetterReplayTaskStatus.evaluate(evidence);
        assertEquals(expected, result.status()); assertEquals(progress, result.progress()); assertTrue(result.lastError().isEmpty());
    }
}
