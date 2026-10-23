package com.patrick.stratum.infrastructure.persistence.idempotency;

import java.time.LocalDateTime;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.idempotency.proxy.IdempotencyRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 不可覆盖的幂等回执持久化映射，复合主键区分作用域及键的大小写。 */
@Getter
@Setter
@EntityProxy
@Table("idempotency_receipt")
public class IdempotencyRecord implements ProxyEntityAvailable<IdempotencyRecord, IdempotencyRecordProxy> {
    @Column(primaryKey = true)
    private String scope;
    @Column(primaryKey = true)
    private String requestKey;
    private String requestDigest;
    private String resultJson;
    private LocalDateTime createdAt;
}
