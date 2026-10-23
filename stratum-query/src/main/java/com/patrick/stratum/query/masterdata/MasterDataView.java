package com.patrick.stratum.query.masterdata;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.masterdata.proxy.MasterDataViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 主数据只读投影，ORM装配后转换为不可变查询结果，不作为写模型使用。 */
@Getter
@Setter
@EntityProxy
@Table("erp_master_data")
public class MasterDataView implements ProxyEntityAvailable<MasterDataView, MasterDataViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String kind;
    private String code;
    private String name;
    private String baseUnit;
    private Long version;
}
