package com.patrick.stratum.query.job;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.job.DeadLetterReplayTaskStatus;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/**
 * 管理员单任务状态查询，仅接受固定任务UUID。
 * <p>示例：{@code DeadLetterReplayStatusQuery.create(taskId)}交由QueryGateway执行。</p>
 */
public final class DeadLetterReplayStatusQuery implements Query<DeadLetterReplayStatusQuery.Row> {
    /** 唯一允许的目标条件。 */
    public enum Field { TASK_ID }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of("taskId",
            new QuerySpec.Field<>(Field.TASK_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false));
    private final QuerySpec<Field> spec;

    /**
     * 保存固定单目标查询规范。
     * @param spec 已验证查询规范
     */
    private DeadLetterReplayStatusQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 创建单任务详情查询，不允许客户端指定任意过滤或排序字段。
     * @param taskId 任务UUID
     * @return 固定一页一条的查询
     * @throws NullPointerException 任务身份为空
     * @apiNote 示例：{@code DeadLetterReplayStatusQuery.create(taskId)}。
     */
    public static DeadLetterReplayStatusQuery create(UUID taskId) {
        return new DeadLetterReplayStatusQuery(QuerySpec.create(1, 1, List.of(),
                List.of(new QuerySpec.FilterInput("taskId", QuerySpec.Operator.EQ, Objects.requireNonNull(taskId), null)), FIELDS));
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }

    /**
     * 值对象：不含原事件、请求键、操作者或领取令牌的安全任务详情。
     * @param taskId 任务UUID
     * @param status 依据真实消费证据判定的状态
     * @param progress 单事件完成百分比，只有消费成功为100
     * @param lastError 可空固定失败分类
     * @param traceId 首次任务请求的安全链路标识
     */
    public record Row(UUID taskId, DeadLetterReplayTaskStatus.Status status, int progress, String lastError, String traceId) {
        /**
         * 校验输出字段并保持单事件进度约束。
         * @param taskId 任务UUID
         * @param status 任务状态
         * @param progress 未完成0、已完成100
         * @param lastError 可空安全失败分类
         * @param traceId 首次请求关联标识
         * @throws NullPointerException 必需字段为空
         * @throws IllegalArgumentException 输出状态或关联标识无效
         * @apiNote 示例：{@code new Row(id, Status.PENDING, 0, null, "trace-1")}。
         */
        public Row {
            Objects.requireNonNull(taskId); Objects.requireNonNull(status);
            if (!com.patrick.stratum.common.trace.CorrelationIdPolicy.isValid(traceId)
                    || progress != (status == DeadLetterReplayTaskStatus.Status.SUCCEEDED ? 100 : 0)
                    || (lastError != null) != (status == DeadLetterReplayTaskStatus.Status.FAILED)) {
                throw new IllegalArgumentException("任务状态详情不合法");
            }
        }
    }
}
