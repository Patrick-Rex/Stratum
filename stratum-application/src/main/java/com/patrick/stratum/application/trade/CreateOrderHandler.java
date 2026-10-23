package com.patrick.stratum.application.trade;

import com.patrick.stratum.application.command.CommandHandler;
import com.patrick.stratum.application.command.UseCaseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将采购销售订单创建接入统一命令路由和工作单元。 */
@Component
@RequiredArgsConstructor
public class CreateOrderHandler implements CommandHandler<CreateOrderCommand, OrderResult> {
    private final TradeOrderApplicationService service;

    /** {@inheritDoc} */
    @Override
    public Class<CreateOrderCommand> commandType() { return CreateOrderCommand.class; }

    /** {@inheritDoc} */
    @Override
    public UseCaseResult<OrderResult> handle(CreateOrderCommand command) { return new UseCaseResult<>(service.create(command)); }
}
