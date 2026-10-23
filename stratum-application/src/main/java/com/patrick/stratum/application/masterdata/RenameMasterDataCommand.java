package com.patrick.stratum.application.masterdata;

import com.patrick.stratum.application.command.Command;
import com.patrick.stratum.domain.masterdata.MasterData;
import lombok.Builder;
import lombok.Getter;

/** 主数据改名命令，仅接受名称与观察版本，固定编码和单位不属于可修改输入。 */
@Getter
@Builder
public final class RenameMasterDataCommand implements Command<MasterDataResult> {
    private final String subjectId;
    private final String idempotencyKey;
    private final MasterData.Kind kind;
    private final String id;
    private final long version;
    private final String name;
}
