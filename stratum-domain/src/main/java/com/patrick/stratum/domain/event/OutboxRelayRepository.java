package com.patrick.stratum.domain.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.common.trace.StandardTraceContext.Carrier;

/** Relay技术存储端口，仅参与Application已有工作单元，不执行MQ或开启事务。 */
public interface OutboxRelayRepository {
    /**
     * 领取已到期可发送的聚合头事件；前序消费成功前不能领取后序。
     * @param request 有界批次及租期
     * @return 不超过请求上限的不可变领取结果；空列表表示本次无可用事件
     * @throws IllegalStateException 没有活动写工作单元
     * @apiNote 示例：unitOfWork.execute(() -> relay.claim(new ClaimRequest(16, Duration.ofSeconds(30))))。
     */
    List<Claim> claim(ClaimRequest request);

    /**
     * 按有效竞争令牌登记发送结果，过期或被接管的领取不得覆盖新状态。
     * @param claim 原领取证据
     * @param result 事务外发布得到的实际结果
     * @param retryPolicy 非确认结果的次数及退避边界
     * @return 更新成功为true；行被占用、租约失效或状态不再匹配为false
     * @throws IllegalStateException 没有活动写工作单元
     * @apiNote 示例：unitOfWork.execute(() -> relay.finish(claim, result, retryPolicy))。
     */
    boolean finish(Claim claim, OutboxPublisher.PublishResult result, RetryPolicy retryPolicy);

    /**
     * 值对象：有界领取请求，不使用调用者机器的时间判定数据库租约。
     * @param batchSize 最多领取条数
     * @param leaseDuration 数据库租期
     */
    record ClaimRequest(int batchSize, Duration leaseDuration) {
        /**
         * 限制单批资源及租期，时限使用毫秒精度。
         * @param batchSize 1至128条
         * @param leaseDuration 1秒至10分钟
         * @throws IllegalArgumentException 参数超限或精度不符
         * @apiNote 示例：new ClaimRequest(16, Duration.ofSeconds(30))。
         */
        public ClaimRequest {
            if (batchSize < 1 || batchSize > 128 || leaseDuration == null
                    || leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                    || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0
                    || leaseDuration.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException("Outbox领取批次或租期超出允许范围");
            }
        }
    }

    /**
     * 值对象：持久化领取证据，租约有效性由仓储依数据库时间重新核验。
     * @param delivery 已落盘的完整事件及序号
     * @param token 唯一竞争令牌
     * @param expiresAt 数据库返回的UTC租约截止时间
     * @param retryCount 已记录的发送失败次数
     * @param standardTrace 原事件标准上下文；历史事件可为空
     */
    record Claim(OutboxPublisher.Delivery delivery, UUID token, Instant expiresAt, int retryCount, Optional<Carrier> standardTrace) {
        /**
         * 保留不可变身份，不在构造时依据本机时钟推测是否过期。
         * @param delivery 原投递
         * @param token 竞争令牌
         * @param expiresAt 数据库租约时刻
         * @param retryCount 非负失败次数
         * @param standardTrace 原事件上下文的可选值
         * @throws NullPointerException 必需证据或可选值缺失
         * @throws IllegalArgumentException 次数为负
         * @apiNote 示例：new Claim(delivery, UUID.randomUUID(), expiresAt, 0)。
         */
        public Claim {
            Objects.requireNonNull(delivery, "领取事件不能为空");
            Objects.requireNonNull(token, "领取令牌不能为空");
            Objects.requireNonNull(expiresAt, "领取租期不能为空");
            Objects.requireNonNull(standardTrace, "标准上下文可选值不能为空");
            if (retryCount < 0) throw new IllegalArgumentException("发送失败次数不能为负数");
        }

        /**
         * 保留原有构造方式，缺少标准上下文时使用空值。
         * @param delivery 原投递
         * @param token 竞争令牌
         * @param expiresAt 数据库租约时刻
         * @param retryCount 非负失败次数
         * @throws NullPointerException 必需证据缺失
         * @throws IllegalArgumentException 次数为负
         * @apiNote 示例：{@code new Claim(delivery, token, expiresAt, 0)}。
         */
        public Claim(OutboxPublisher.Delivery delivery, UUID token, Instant expiresAt, int retryCount) {
            this(delivery, token, expiresAt, retryCount, Optional.empty());
        }

        /**
         * 输出不含正文与竞争令牌的身份摘要。
         * @return 安全摘要，例如OutboxClaim[eventId=UUID, sequence=1]
         */
        @Override
        public String toString() {
            return "OutboxClaim[eventId=" + delivery.message().eventId() + ", sequence=" + delivery.sequence() + "]";
        }
    }

    /**
     * 值对象：发布失败退避和累计失败次数阈值，不跳过聚合头。累计次数包含临时故障。原事件的临时或不确定故障持续封顶重试；消费死信重放使用原有阈值策略。
     * @param maxFailures 明确故障或消费重放判断所用的累计失败次数阈值
     * @param initialDelay 首次重试延迟
     * @param maxDelay 指数退避上限
     */
    record RetryPolicy(int maxFailures, Duration initialDelay, Duration maxDelay) {
        /**
         * 校验次数、毫秒精度和延迟边界。
         * @param maxFailures 1至100
         * @param initialDelay 100毫秒至1分钟
         * @param maxDelay 不低于initialDelay且最多1小时
         * @throws IllegalArgumentException 参数越界
         * @apiNote 示例：new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30))。
         */
        public RetryPolicy {
            if (maxFailures < 1 || maxFailures > 100 || initialDelay == null || maxDelay == null
                    || initialDelay.compareTo(Duration.ofMillis(100)) < 0
                    || initialDelay.compareTo(Duration.ofMinutes(1)) > 0
                    || maxDelay.compareTo(initialDelay) < 0 || maxDelay.compareTo(Duration.ofHours(1)) > 0
                    || initialDelay.getNano() % 1_000_000 != 0 || maxDelay.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException("Outbox重试次数或退避超出允许范围");
            }
        }

        /**
         * 计算第几次失败后的指数退避，计算次数到达上限后立即停止，不产生数值溢出。
         * @param failureCount 当前正失败次数
         * @return 不超过maxDelay的确定性退避
         * @throws IllegalArgumentException 次数不是正数
         * @apiNote 示例：policy.delayForFailure(2)返回两倍首次延迟或上限。
         */
        public Duration delayForFailure(int failureCount) {
            if (failureCount < 1) throw new IllegalArgumentException("退避失败次数必须为正数");
            long delay = initialDelay.toMillis();
            long maximum = maxDelay.toMillis();
            for (int failure = 1; failure < failureCount && delay < maximum; failure++) {
                delay = Math.min(maximum, delay * 2);
            }
            return Duration.ofMillis(delay);
        }
    }
}
