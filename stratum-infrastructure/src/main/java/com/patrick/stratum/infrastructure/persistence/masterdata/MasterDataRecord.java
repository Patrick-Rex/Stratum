package com.patrick.stratum.infrastructure.persistence.masterdata;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.UpdateIgnore;
import com.easy.query.core.annotation.Version;
import com.easy.query.core.basic.extension.version.VersionLongStrategy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.masterdata.proxy.MasterDataRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 主数据持久化映射：固定字段不参与UPDATE，version由ORM校验及递增。 */
@Getter
@Setter
@EntityProxy
@Table("erp_master_data")
public class MasterDataRecord implements ProxyEntityAvailable<MasterDataRecord, MasterDataRecordProxy> {
    @Column(primaryKey = true)
    private String id;
    @UpdateIgnore
    private String kind;
    @UpdateIgnore
    private String code;
    private String name;
    @UpdateIgnore
    private String baseUnit;
    @Version(strategy = VersionLongStrategy.class)
    private Long version;
}
