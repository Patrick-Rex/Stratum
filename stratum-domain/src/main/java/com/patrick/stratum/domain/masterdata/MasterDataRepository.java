package com.patrick.stratum.domain.masterdata;

import com.patrick.stratum.domain.repository.Repository;

/** 主数据聚合仓储端口，保存遵循同类编码唯一和乐观锁，删除必须受业务引用约束。 */
public interface MasterDataRepository extends Repository<MasterData, String> { }
