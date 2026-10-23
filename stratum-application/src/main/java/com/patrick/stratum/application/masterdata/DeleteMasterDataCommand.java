package com.patrick.stratum.application.masterdata;

import com.patrick.stratum.application.command.Command;
import com.patrick.stratum.domain.masterdata.MasterData;
import lombok.Builder;
import lombok.Getter;

/** 主数据删除命令，显式携带观察版本与服务端主体，相同幂等键可安全重试。 */
@Getter
@Builder
public final class DeleteMasterDataCommand implements Command<Void> {
    private final String subjectId;
    private final String idempotencyKey;
    private final MasterData.Kind kind;
    private final String id;
    private final long version;
}
