package com.patrick.stratum.domain.event;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 死信技术存储端口，只归档证据，不推进业务消费顺序或直接发送消息。 */
public interface OutboxDeadLetterRepository {
    /**
     * 在已有应用写工作单元中归档；相同订阅、原体和元数据返回同一个归档ID。
     * @param letter 原死信证据，解析身份仍是不可信声明
     * @return 首次归档或既有归档UUID
     * @throws IllegalStateException 无写工作单元或归档标识冲突
     * @throws IllegalArgumentException 元数据不是严格JSON对象
     * @apiNote 示例：unitOfWork.execute(() -> repository.append(letter))。
     */
    UUID append(Letter letter);

    /** 归档分类；可解析并不代表已通过本地原事件核验。 */
    enum Category { RETRIES_EXHAUSTED, INVALID_ENVELOPE }

    /**
     * 值对象：不可变、有界的原始死信证据，不作为重放授权。
     * @param id 本次建议归档UUID，重复收存可能返回已有ID
     * @param group 固定来源订阅
     * @param body Broker原消息字节，允许空或非法UTF-8以保留无效信封
     * @param metadataJson 有界传输元数据快照JSON，不参与业务规则
     * @param delivery 可解析的原身份，重放时仍需核对本地Outbox
     * @param category 固定失败分类
     * @param failureCount Broker提供的非负失败次数
     */
    record Letter(UUID id, OutboxConsumptionRepository.Group group, byte[] body, String metadataJson,
            Optional<OutboxPublisher.Delivery> delivery, Category category, long failureCount) {
        public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
        public static final int MAX_METADATA_BYTES = 128 * 1024;

        /**
         * 防御复制原体并校验归档资源上限，不丢弃非法信封原字节。
         * @param id 归档UUID
         * @param group 来源订阅
         * @param body 最大2MiB原体
         * @param metadataJson 最大128KiB元数据
         * @param delivery 可解析原身份
         * @param category 固定分类
         * @param failureCount 非负计数
         * @throws IllegalArgumentException 超限、分类/身份不一致或负计数
         * @throws NullPointerException 必需字段为空
         * @apiNote 示例：new Letter(UUID.randomUUID(), group, body, "{}", Optional.empty(), Category.INVALID_ENVELOPE, 0)。
         */
        public Letter {
            Objects.requireNonNull(id); Objects.requireNonNull(group); Objects.requireNonNull(body);
            Objects.requireNonNull(delivery); Objects.requireNonNull(category);
            if (body.length > MAX_BODY_BYTES || metadataJson == null || metadataJson.isBlank()
                    || metadataJson.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES || failureCount < 0
                    || (category == Category.RETRIES_EXHAUSTED) != delivery.isPresent()
                    || delivery.isPresent() && !group.accepts(delivery.orElseThrow().message().eventType())) {
                throw new IllegalArgumentException("死信归档字段或资源上限不合法");
            }
            body = body.clone();
        }

        /**
         * 读取时再次复制，防止外部修改归档证据。
         * @return 原体副本
         * @apiNote 示例：byte[] original = letter.body()。
         */
        public byte[] body() { return body.clone(); }

        /** {@inheritDoc} */
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Letter value && id.equals(value.id) && group == value.group
                    && Arrays.equals(body, value.body) && metadataJson.equals(value.metadataJson)
                    && delivery.equals(value.delivery) && category == value.category && failureCount == value.failureCount;
        }

        /** {@inheritDoc} */
        @Override public int hashCode() { return Objects.hash(id, group, Arrays.hashCode(body), metadataJson, delivery, category, failureCount); }

        /**
         * 输出不含原体、传输元数据或trace原文的安全摘要。
         * @return 仅归档ID、组别和分类的摘要
         */
        @Override public String toString() { return "OutboxDeadLetter[id=" + id + ", group=" + group + ", category=" + category + "]"; }
    }
}
