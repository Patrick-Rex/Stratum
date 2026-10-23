package com.patrick.stratum.domain.trade;

/** 履约凭据仓储端口，只允许同事务追加完整凭据及明细。 */
public interface FulfillmentRepository {
    /**
     * 追加凭据，同一订单版本不得重复登记；所有行必须属于来源订单。
     * @param fulfillment 不可变履约凭据
     * @throws com.patrick.stratum.common.error.StratumException 唯一或来源引用冲突
     * @apiNote 示例：repository.append(fulfillment)。
     */
    void append(Fulfillment fulfillment);
}
