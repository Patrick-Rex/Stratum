package com.patrick.stratum.starter.integration;

/** 经实际Gateway JAR复用双仓库收货/出库、API及范围撤回和原键重放矩阵。 */
class GatewayTradeOrderFulfillScopeHttpIntegrationTest extends TradeOrderFulfillScopeHttpIntegrationTest {
    /** {@inheritDoc} */
    @Override protected boolean gatewayEnabled() { return true; }
}
