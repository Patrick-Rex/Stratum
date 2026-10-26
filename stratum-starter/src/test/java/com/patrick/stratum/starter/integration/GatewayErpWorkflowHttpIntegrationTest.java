package com.patrick.stratum.starter.integration;

import java.net.URI;

import com.patrick.stratum.common.base.cache.VersionedCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 复用完整进销存与版本断言，通过独立Gateway JAR访问真实ERP，不改变业务用例。 */
class GatewayErpWorkflowHttpIntegrationTest extends ErpWorkflowHttpIntegrationTest {
    @LocalServerPort private int upstreamPort;
    private final StringRedisTemplate gatewayRedis;
    private GatewayProcessFixture gateway;

    /**
     * 复用ERP夹具的独立数据库及缓存清理依赖。
     * @param jdbc 数据库断言客户端
     * @param cache 版本化缓存
     * @param redis 测试Redis
     */
    @Autowired GatewayErpWorkflowHttpIntegrationTest(JdbcTemplate jdbc, VersionedCache cache, StringRedisTemplate redis) {
        super(jdbc, cache, redis); this.gatewayRedis = redis;
    }

    /**
     * 在ERP与实际JWT就绪后启动独立网关，不将其加入Starter组件扫描。
     * @throws Exception 子进程启动失败
     */
    @BeforeEach void startGateway() throws Exception { gateway = GatewayProcessFixture.start(upstreamPort, gatewayRedis); }

    /**
     * 先验证真实日志关联和类路径隔离，再退出自身进程，之后父夹具清理业务数据。
     * @throws Exception 输出或退出失败
     */
    @AfterEach void stopGateway() throws Exception {
        if (gateway == null) return;
        try {
            assertTrue(gateway.hasTrace(trace), "独立Gateway日志未关联业务trace");
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.patrick.stratum.gateway.GatewayApplication"));
        } finally { gateway.close(); }
    }

    /** {@inheritDoc} */
    @Override protected URI endpoint(String path) { return gateway.endpoint(path); }
}
