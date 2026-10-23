package com.patrick.stratum.application.trade;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/** 履约用例结果，保存履约标识及当次订单快照，重放不会返回后续订单状态。 */
@Getter
public final class FulfillmentResult {
    private final UUID postingId;
    private final OrderResult order;

    /**
     * 构造可精确重放的固定结果。
     * @param postingId 本次履约UUID
     * @param order 本次提交后的订单快照
     * @apiNote 示例：new FulfillmentResult(posting.getId(), OrderResult.from(order))。
     */
    @Builder
    @JsonCreator
    public FulfillmentResult(@JsonProperty("postingId") UUID postingId, @JsonProperty("order") OrderResult order) {
        this.postingId = postingId; this.order = order;
    }
}
