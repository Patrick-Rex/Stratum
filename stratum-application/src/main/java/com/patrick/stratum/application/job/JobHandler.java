package com.patrick.stratum.application.job;

import java.util.Optional;
import java.util.Objects;

import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;

/**
 * 任务处理端口：由Job执行器在状态工作单元之外调用，只编排已有Application能力。
 * <p>示例：为outbox.relay登记处理器，execute中调用既有OutboxRelayApplicationService。</p>
 * <p>处理必须按原执行UUID/业务幂等键保护副作用，支持中断并约束外部I/O；不直接发布MQ。</p>
 */
public interface JobHandler {
    /**
     * 返回注册后固定的稳定任务代码，不从调用者参数决定可执行类型。
     * @return 与JobDefinition一致的安全代码
     * @apiNote 示例：返回"outbox.relay"。
     */
    String jobCode();

    /**
     * 声明可选技术Cron定义；空值只表示可处理已有执行，不自动登记新计划。
     * @return 不可变Cron定义或空可选值
     * @apiNote 示例：一次性任务处理器沿用默认空值；定时Relay返回CRON/SCHEDULE_TIME定义。
     */
    default Optional<JobDefinition> periodicDefinition() { return Optional.empty(); }

    /**
     * 接收工作器已提交的原领取证据；默认委托既有输入入口，需校验租约的处理器可覆盖。
     * @param lease 原领取证据，不得记录或泄露竞争令牌
     * @throws NullPointerException 领取证据为空
     * @throws InterruptedException 处理被中断
     * @throws RuntimeException 本次处理失败
     * @apiNote 示例：工作器调用{@code handler.executeLease(lease)}，由持租约处理器复核资格。
     */
    default void executeLease(Lease lease) throws InterruptedException {
        execute(Objects.requireNonNull(lease, "任务领取不能为空").submission());
    }

    /**
     * 执行与本处理器代码匹配的已登记输入；返回表示该次处理完成，状态由执行器独立写回。
     * @param submission 原始不可变执行输入，人工恢复仍使用原执行UUID
     * @throws InterruptedException 超时或关闭中断时向执行器传播，不吞掉中断信号
     * @throws RuntimeException 本次处理失败，执行器只保存固定分类
     * @apiNote 示例：验证固定参数后调用已有Application用例，业务写入使用该用例自身工作单元。
     */
    void execute(Submission submission) throws InterruptedException;
}
