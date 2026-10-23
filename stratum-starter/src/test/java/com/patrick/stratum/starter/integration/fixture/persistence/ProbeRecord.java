package com.patrick.stratum.starter.integration.fixture.persistence;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.Version;
import com.easy.query.core.basic.extension.version.VersionLongStrategy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.starter.integration.fixture.persistence.proxy.ProbeRecordProxy;

import lombok.Getter;
import lombok.Setter;

/** Easy-Query 数据表映射测试夹具，代理与访问器由处理器生成。 */
@Getter
@Setter
@Table("stratum_it_repository_probe")
@EntityProxy
public class ProbeRecord implements ProxyEntityAvailable<ProbeRecord, ProbeRecordProxy> {
    @Column(primaryKey = true)
    private String id;
    private String payload;
    @Version(strategy = VersionLongStrategy.class)
    private Long version;
}
