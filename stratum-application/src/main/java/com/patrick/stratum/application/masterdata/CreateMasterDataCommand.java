package com.patrick.stratum.application.masterdata;

import com.patrick.stratum.application.command.Command;
import com.patrick.stratum.domain.masterdata.MasterData;
import lombok.Builder;
import lombok.Getter;

/** 创建主数据的写命令；subjectId由服务器提供，不能直接从HTTP请求体采信。 */
@Getter
@Builder
public final class CreateMasterDataCommand implements Command<MasterDataResult> {
    private final String subjectId;
    private final String idempotencyKey;
    private final MasterData.Kind kind;
    private final String code;
    private final String name;
    private final String baseUnit;
}
