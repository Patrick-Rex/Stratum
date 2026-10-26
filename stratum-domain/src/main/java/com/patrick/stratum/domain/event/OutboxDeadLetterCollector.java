package com.patrick.stratum.domain.event;

import java.util.UUID;

/** 死信归档应用端口，供传输适配在数据库提交后确认原DLQ消息。 */
public interface OutboxDeadLetterCollector {
    /**
     * 从事务外归档原始证据，只有提交成功后才返回稳定归档ID。
     * @param letter 有界原始证据，不作为业务重放授权
     * @return 首次或重复归档的稳定UUID
     * @throws IllegalStateException 已有外层事务或归档状态不合法
     * @apiNote 示例：UUID archiveId = collector.archive(letter)，返回后监听器才能ack。
     */
    UUID archive(OutboxDeadLetterRepository.Letter letter);
}
