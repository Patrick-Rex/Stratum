package com.patrick.stratum.application.masterdata;

import com.patrick.stratum.application.command.CommandHandler;
import com.patrick.stratum.application.command.UseCaseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将主数据创建命令接入CommandGateway工作单元。 */
@Component
@RequiredArgsConstructor
public class CreateMasterDataHandler implements CommandHandler<CreateMasterDataCommand, MasterDataResult> {
    private final MasterDataApplicationService service;

    /** {@inheritDoc} */
    @Override
    public Class<CreateMasterDataCommand> commandType() { return CreateMasterDataCommand.class; }

    /** {@inheritDoc} */
    @Override
    public UseCaseResult<MasterDataResult> handle(CreateMasterDataCommand command) {
        return new UseCaseResult<>(service.create(command));
    }
}
