package com.patrick.stratum.application.trade;

import com.patrick.stratum.application.command.CommandHandler;
import com.patrick.stratum.application.command.UseCaseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 通过统一命令工作单元执行采购收货或销售出库。 */
@Component
@RequiredArgsConstructor
public class FulfillOrderHandler implements CommandHandler<FulfillOrderCommand, FulfillmentResult> {
    private final TradeOrderApplicationService service;

    /** {@inheritDoc} */
    @Override
    public Class<FulfillOrderCommand> commandType() { return FulfillOrderCommand.class; }

    /** {@inheritDoc} */
    @Override
    public UseCaseResult<FulfillmentResult> handle(FulfillOrderCommand command) { return new UseCaseResult<>(service.fulfill(command)); }
}
