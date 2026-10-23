package com.patrick.stratum.application.masterdata;

import com.patrick.stratum.application.command.CommandHandler;
import com.patrick.stratum.application.command.UseCaseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将主数据改名接入统一命令工作单元，事务策略由应用层维护。 */
@Component
@RequiredArgsConstructor
public class RenameMasterDataHandler implements CommandHandler<RenameMasterDataCommand, MasterDataResult> {
    private final MasterDataApplicationService service;

    /** {@inheritDoc} */
    @Override
    public Class<RenameMasterDataCommand> commandType() { return RenameMasterDataCommand.class; }

    /** {@inheritDoc} */
    @Override
    public UseCaseResult<MasterDataResult> handle(RenameMasterDataCommand command) {
        return new UseCaseResult<>(service.rename(command));
    }
}
