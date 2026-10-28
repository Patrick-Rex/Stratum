package com.patrick.stratum.domain.job;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 值契约：在已有写工作单元中绑定首次重放请求、操作者、归档和任务。
 * <p>示例：{@code unitOfWork.execute(() -> tasks.register(request))}不表示实际消费。</p>
 */
public interface DeadLetterReplayTaskRepository {
    /**
     * 原子登记任务与请求绑定；相同请求和操作者返回首次任务，不能更换归档或操作者。
     * @param request 可信操作者、归档及固定任务输入
     * @return 首次登记的任务UUID
     * @throws IllegalStateException 不在已有写工作单元内
     * @throws com.patrick.stratum.common.error.StratumException 请求身份、任务身份或持久化状态冲突
     * @apiNote 示例：{@code tasks.register(request)}后需等待任务和消费结果。
     */
    UUID register(Request request);

    /**
     * 读取并锁定指定任务的首次绑定，锁持续到调用方的工作单元结束。
     * @param taskId 已登记任务UUID
     * @return 已绑定的可信身份，任务不存在时为空
     * @throws NullPointerException 任务身份为空
     * @throws IllegalStateException 不在已有写工作单元内
     * @throws com.patrick.stratum.common.error.StratumException 行锁竞争
     * @apiNote 示例：{@code unitOfWork.execute(() -> tasks.lockBinding(taskId))}。
     */
    Optional<Binding> lockBinding(UUID taskId);

    /**
     * 值对象：首次提交的请求、归档、操作者和任务绑定。
     * @param requestId 首次请求UUID
     * @param archiveId 归档UUID
     * @param operatorId 首次可信操作者UUID
     * @param taskId 任务UUID
     */
    record Binding(UUID requestId, UUID archiveId, UUID operatorId, UUID taskId) {
        /**
         * 防止返回不完整的执行绑定。
         * @param requestId 请求UUID
         * @param archiveId 归档UUID
         * @param operatorId 操作者UUID
         * @param taskId 任务UUID
         * @throws NullPointerException 任一身份为空
         * @apiNote 示例：{@code new Binding(requestId, archiveId, actorId, taskId)}。
         */
        public Binding {
            Objects.requireNonNull(requestId); Objects.requireNonNull(archiveId); Objects.requireNonNull(operatorId); Objects.requireNonNull(taskId);
        }
    }

    /**
     * 值对象：一次重放任务请求及服务端构造的不可变任务快照。
     * @param requestId 请求幂等UUID
     * @param archiveId 死信归档UUID
     * @param operatorId 服务器认证的操作者UUID
     * @param submission 固定代码、归档参数和请求幂等键组成的任务
     */
    record Request(UUID requestId, UUID archiveId, UUID operatorId, JobExecutionRepository.Submission submission) {
        /**
         * 验证所有身份和固定任务输入的绑定，不接受替换事件正文或动态代码。
         * @param requestId 请求幂等UUID
         * @param archiveId 归档UUID
         * @param operatorId 可信操作者UUID
         * @param submission 已验证的单次任务快照
         * @throws NullPointerException 必需输入为空
         * @throws IllegalArgumentException 任务代码、类型、键或参数与请求不匹配
         * @apiNote 示例：{@code new Request(requestId, archiveId, actorId, submission)}。
         */
        public Request {
            Objects.requireNonNull(requestId); Objects.requireNonNull(archiveId); Objects.requireNonNull(operatorId); Objects.requireNonNull(submission);
            var definition = submission.definition();
            if (!DeadLetterReplayJob.CODE.equals(definition.jobCode()) || definition.jobType() != JobDefinition.Type.ONCE
                    || definition.idempotencyKeyRule() != JobDefinition.IdempotencyRule.EXPLICIT_KEY
                    || !requestId.toString().equals(submission.idempotencyKey())
                    || !DeadLetterReplayJob.arguments(archiveId).equals(submission.arguments())) {
                throw new IllegalArgumentException("重放任务与请求身份不匹配");
            }
        }
    }
}
