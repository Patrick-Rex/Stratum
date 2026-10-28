package com.patrick.stratum.domain.job;

import java.util.Objects;
import java.util.Optional;

import com.patrick.stratum.domain.event.OutboxReplayRepository;

/**
 * 值策略：从单次任务、重放记录和已验证消费证据推导可公开状态。
 * <p>示例：{@code DeadLetterReplayTaskStatus.evaluate(evidence)}；仅可信消费事实可返回成功。</p>
 */
public final class DeadLetterReplayTaskStatus {
    /** 面向任务调用方的有限状态。 */
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    /** 可公开的固定失败分类，不包含异常正文。 */
    public enum Error { EXECUTION_FAILED, REPLAY_FAILED }
    /** 独立重放投递的持久状态。 */
    public enum Delivery { PENDING, PROCESSING, SENT, FAILED, RESOLVED }
    /** 对原事件、消费组和顺序核对后的消费证据状态。 */
    public enum Consumption { NONE, VALID, INVALID }

    /**
     * 值对象：来自同一数据库快照的有限事实；目标一致性和消费校验由读取边界提供。
     * @param executionState 任务技术状态
     * @param attempts 累计尝试次数
     * @param replayOutcome 已提交重放请求结果，可空表示尚未登记
     * @param delivery 独立重放投递状态，可空表示没有投递
     * @param targetMatches 重放请求及投递与首次归档绑定一致
     * @param consumption 消费证据核验结果
     */
    public record Evidence(JobExecutionRepository.State executionState, long attempts,
            Optional<OutboxReplayRepository.Outcome> replayOutcome, Optional<Delivery> delivery,
            boolean targetMatches, Consumption consumption) {
        /**
         * 校验基本状态及计数，禁止无法发生的开始/取消组合。
         * @param executionState 非空技术状态
         * @param attempts 非负累计尝试
         * @param replayOutcome 可选重放回执
         * @param delivery 可选投递状态
         * @param targetMatches 已核验目标标志
         * @param consumption 已核验消费状态
         * @throws NullPointerException 必需字段为空
         * @throws IllegalArgumentException 尝试次数与状态不一致
         * @apiNote 示例：{@code new Evidence(State.WAITING, 0, Optional.empty(), Optional.empty(), false, Consumption.NONE)}。
         */
        public Evidence {
            Objects.requireNonNull(executionState); Objects.requireNonNull(replayOutcome); Objects.requireNonNull(delivery); Objects.requireNonNull(consumption);
            if (attempts < 0 || (executionState == JobExecutionRepository.State.CANCELLED && attempts != 0)
                    || (executionState != JobExecutionRepository.State.WAITING && executionState != JobExecutionRepository.State.CANCELLED && attempts == 0)) {
                throw new IllegalArgumentException("任务状态与尝试次数不一致");
            }
        }
    }

    /**
     * 值对象：单事件任务状态、完成百分比及安全失败分类。
     * @param status 面向调用方的状态
     * @param progress 未消费为0，可信消费完成为100
     * @param lastError 仅失败时提供固定分类
     */
    public record Result(Status status, int progress, Optional<Error> lastError) {
        /**
         * 确保进度与成功、失败分类一致。
         * @param status 任务状态
         * @param progress 仅0或100
         * @param lastError 可选安全失败分类
         * @throws NullPointerException 状态或可选值为空
         * @throws IllegalArgumentException 状态、进度或失败分类不一致
         * @apiNote 示例：{@code new Result(Status.SUCCEEDED, 100, Optional.empty())}。
         */
        public Result {
            Objects.requireNonNull(status); Objects.requireNonNull(lastError);
            if (progress != (status == Status.SUCCEEDED ? 100 : 0) || lastError.isPresent() != (status == Status.FAILED)) {
                throw new IllegalArgumentException("任务状态、进度与错误分类不一致");
            }
        }
    }

    /** 策略类不可实例化。 */
    private DeadLetterReplayTaskStatus() { }

    /**
     * 基于一致性证据给出状态；不一致或缺失的成功证据失败关闭。
     * @param evidence 同一快照中的任务、回执、投递和消费事实
     * @return 单事件状态及进度
     * @throws NullPointerException 证据为空
     * @throws IllegalStateException 持久事实互相矛盾或成功证据不完整
     * @apiNote 示例：{@code evaluate(evidence)}的SUCCEEDED只表示已验证消费。
     */
    public static Result evaluate(Evidence evidence) {
        Objects.requireNonNull(evidence, "任务证据不能为空");
        if (evidence.replayOutcome().isEmpty()) return beforeRegistration(evidence);
        if (evidence.executionState() == JobExecutionRepository.State.CANCELLED || evidence.attempts() == 0 || !evidence.targetMatches()
                || evidence.consumption() == Consumption.INVALID) throw inconsistent();
        var outcome = evidence.replayOutcome().orElseThrow();
        if (outcome == OutboxReplayRepository.Outcome.ALREADY_CONSUMED) {
            if (evidence.delivery().isPresent() || evidence.consumption() != Consumption.VALID) throw inconsistent();
            return result(Status.SUCCEEDED);
        }
        var delivery = evidence.delivery().orElseThrow(DeadLetterReplayTaskStatus::inconsistent);
        if (delivery == Delivery.RESOLVED) {
            if (evidence.consumption() != Consumption.VALID) throw inconsistent();
            return result(Status.SUCCEEDED);
        }
        if (evidence.consumption() != Consumption.NONE) throw inconsistent();
        if (delivery == Delivery.FAILED) return new Result(Status.FAILED, 0, Optional.of(Error.REPLAY_FAILED));
        return result(Status.RUNNING);
    }

    /**
     * 以任务技术状态区分尚未开始、处理中、失败和取消，不把技术成功当成消费成功。
     * @param evidence 尚无重放回执的快照
     * @return 公开状态
     */
    private static Result beforeRegistration(Evidence evidence) {
        if (evidence.delivery().isPresent()) throw inconsistent();
        return switch (evidence.executionState()) {
            case WAITING -> result(evidence.attempts() == 0 ? Status.PENDING : Status.RUNNING);
            case RUNNING, RETRY_WAIT -> result(Status.RUNNING);
            case DEAD -> new Result(Status.FAILED, 0, Optional.of(Error.EXECUTION_FAILED));
            case CANCELLED -> result(Status.CANCELLED);
            case SUCCEEDED -> throw inconsistent();
        };
    }

    /**
     * 构造未失败状态的单事件进度。
     * @param status 非失败状态
     * @return 不含错误的状态
     */
    private static Result result(Status status) {
        return new Result(status, status == Status.SUCCEEDED ? 100 : 0, Optional.empty());
    }

    /**
     * 返回不含消息正文或存储内容的一致性错误。
     * @return 安全异常
     */
    private static IllegalStateException inconsistent() { return new IllegalStateException("重放任务证据不一致"); }
}
