package com.patrick.stratum.application.masterdata;

import com.patrick.stratum.application.command.CommandHandler;
import com.patrick.stratum.application.command.UseCaseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将主数据幂等删除接入统一写事务入口。 */
@Component
@RequiredArgsConstructor
public class DeleteMasterDataHandler implements CommandHandler<DeleteMasterDataCommand, Void> {
    private final MasterDataApplicationService service;

    /** {@inheritDoc} */
    @Override
    public Class<DeleteMasterDataCommand> commandType() { return DeleteMasterDataCommand.class; }

    /** {@inheritDoc} */
    @Override
    public UseCaseResult<Void> handle(DeleteMasterDataCommand command) {
        return new UseCaseResult<>(service.delete(command));
    }
}
