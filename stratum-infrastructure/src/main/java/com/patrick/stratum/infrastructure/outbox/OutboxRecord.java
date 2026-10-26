package com.patrick.stratum.infrastructure.outbox;

import java.time.LocalDateTime;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.Version;
import com.easy.query.core.basic.extension.version.VersionLongStrategy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.outbox.proxy.OutboxRecordProxy;

import lombok.Getter;
import lombok.Setter;

/** Outbox 持久化映射；所有 LocalDateTime 字段以 UTC 解释，访问器及代理由生成器维护。 */
@Getter
@Setter
@EntityProxy
@Table("outbox_event")
public class OutboxRecord implements ProxyEntityAvailable<OutboxRecord, OutboxRecordProxy> {
    @Column(primaryKey = true)
    private String eventId;
    private String eventType;
    private String aggregateId;
    private Long eventSequence;
    private LocalDateTime occurredAt;
    private String payload;
    private String traceId;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
    @Version(strategy = VersionLongStrategy.class)
    private Long version;
}
