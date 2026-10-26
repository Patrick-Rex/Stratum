package com.patrick.stratum.domain.job;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.common.trace.CorrelationIdPolicy;

/**
 * 任务执行技术存储端口：登记、候选与领取均参与Application已有写工作单元。
 * <p>示例：unitOfWork.execute(() -> executions.register(submission))；执行器在事务外运行。</p>
 */
public interface JobExecutionRepository {
    /**
     * 幂等登记不可变输入，相同任务代码/键且输入一致时返回首次执行身份。
     * @param submission 完整任务快照；trace不参与幂等内容比较，保存首次trace
     * @return 首次登记的执行UUID
     * @throws IllegalStateException 缺少活动写工作单元
     * @apiNote 示例：unitOfWork.execute(() -> executions.register(submission))。
     */
    UUID register(Submission submission);

    /**
     * 返回指定任务代码已到期或领取已过期的有限候选，不读取业务模型。
     * @param jobCode 已登记任务代码
     * @param limit 最多1至128个身份
     * @return 有界不可变执行ID列表，仍需claim当前读核验
     * @throws IllegalStateException 缺少活动写工作单元
     * @throws IllegalArgumentException 代码或上限不合法
     * @apiNote 示例：unitOfWork.execute(() -> executions.due("outbox.relay", 16))。
     */
    List<UUID> due(String jobCode, int limit);

    /**
     * 领取一个当前可执行记录；数据库时间决定资格，忙行不等待。
     * 已过期尝试必须先留痕并计为失败，再依据策略决定重试或dead_job。
     * @param executionId 原执行身份
     * @return 新竞争令牌及完整输入；未到期、忙或终态时为空
     * @throws IllegalStateException 缺少活动写工作单元
     * @apiNote 示例：unitOfWork.execute(() -> executions.claim(executionId))。
     */
    Optional<Lease> claim(UUID executionId);

    /** 值枚举：执行状态；DEAD只经人工恢复进入新周期，SUCCEEDED终态不回退。 */
    enum State { WAITING, RUNNING, RETRY_WAIT, SUCCEEDED, DEAD }

    /**
     * 值对象：不可变执行登记，不允许将正文或幂等键写入默认日志摘要。
     * @param executionId 调用方生成的执行UUID
     * @param definition 任务定义快照
     * @param scheduleTime 本次计划时刻
     * @param idempotencyKey 任务代码内唯一的有界ASCII键
     * @param arguments JSON参数，语法由持久化边界严格校验
     * @param traceId 安全关联标识
     */
    record Submission(UUID executionId, JobDefinition definition, Instant scheduleTime,
            String idempotencyKey, String arguments, String traceId) {
        /**
         * 固定执行身份和资源边界，计划时刻键必须与输入的UTC时刻完全相同。
         * @param executionId 非空UUID
         * @param definition 任务定义
         * @param scheduleTime 数据库范围内UTC微秒时刻
         * @param idempotencyKey 1至128个可见ASCII字符
         * @param arguments 1至16KiB UTF-8 JSON文本，不含未配对代理字符
         * @param traceId 现有CorrelationIdPolicy接受的标识
         * @throws NullPointerException 身份或定义缺失
         * @throws IllegalArgumentException 键、正文或关联标识不合法
         * @apiNote 示例：new Submission(UUID.randomUUID(), definition, time, "request-1", "{}", "trace-1")。
         */
        public Submission {
            Objects.requireNonNull(executionId, "执行身份不能为空");
            Objects.requireNonNull(definition, "任务定义不能为空");
            JobDefinition.requireTime(scheduleTime);
            if (idempotencyKey == null || !idempotencyKey.matches("[!-~]{1,128}")
                    || arguments == null || arguments.isBlank() || arguments.length() > 16_384 || arguments.getBytes(StandardCharsets.UTF_8).length > 16_384
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(arguments) || !CorrelationIdPolicy.isValid(traceId)
                    || (definition.idempotencyKeyRule() == JobDefinition.IdempotencyRule.SCHEDULE_TIME
                        && !idempotencyKey.equals(definition.jobCode() + ":" + scheduleTime))) {
                throw new IllegalArgumentException("任务参数、幂等键或关联标识不合法");
            }
            if (definition.jobType() == JobDefinition.Type.ONCE && !definition.triggerAt().orElseThrow().equals(scheduleTime)) {
                throw new IllegalArgumentException("一次性任务计划时刻不一致");
            }
        }

        /**
         * 输出不含正文、计划表达式、幂等键或trace的固定身份摘要。
         * @return 安全摘要，例如JobSubmission[executionId=UUID, jobCode=outbox.relay]
         */
        @Override
        public String toString() {
            return "JobSubmission[executionId=" + executionId + ", jobCode=" + definition.jobCode() + "]";
        }
    }

    /**
     * 值对象：数据库生成的领取证据，旧令牌和旧周期不得修改新执行状态。
     * @param submission 已持久化输入
     * @param token 唯一领取令牌
     * @param startTime 数据库开始时间
     * @param expiresAt 数据库租期截止时间
     * @param attempt 跨人工恢复周期累计的正尝试序号
     * @param failures 当前周期已完成失败次数
     * @param cycle 人工恢复周期，从0开始
     */
    record Lease(Submission submission, UUID token, Instant startTime, Instant expiresAt,
            long attempt, int failures, long cycle) {
        /**
         * 核验不可变证据格式；实际所有权必须在数据库当前读下重新核验。
         * @param submission 原输入
         * @param token 唯一令牌
         * @param startTime 数据库UTC微秒开始时刻
         * @param expiresAt 不晚于开始后330秒的UTC微秒截止时间
         * @param attempt 正尝试序号
         * @param failures 非负且小于本周期最大次数
         * @param cycle 非负人工恢复周期
         * @throws NullPointerException 输入或令牌缺失
         * @throws IllegalArgumentException 时间、次数或周期不合法
         * @apiNote 示例：new Lease(submission, UUID.randomUUID(), start, end, 1, 0, 0)。
         */
        public Lease {
            Objects.requireNonNull(submission, "领取输入不能为空");
            Objects.requireNonNull(token, "领取令牌不能为空");
            JobDefinition.requireTime(startTime);
            JobDefinition.requireTime(expiresAt);
            if (!expiresAt.isAfter(startTime) || expiresAt.isAfter(startTime.plusSeconds(330))
                    || attempt < 1 || failures < 0 || failures >= submission.definition().retryPolicy().maxAttempts() || cycle < 0) {
                throw new IllegalArgumentException("任务领取证据不合法");
            }
        }

        /**
         * 隐藏竞争令牌和正文，仅保留可追溯身份与尝试序号。
         * @return 安全领取摘要
         */
        @Override
        public String toString() {
            return "JobLease[executionId=" + submission.executionId() + ", attempt=" + attempt + ", cycle=" + cycle + "]";
        }
    }
}
