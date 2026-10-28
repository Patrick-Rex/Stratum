package com.patrick.stratum.starter.integration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.event.DeadLetterBacklogSamplingService;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.infrastructure.messaging.RabbitDeadLetterBacklogProbe;
import com.patrick.stratum.infrastructure.messaging.RedisDeadLetterBacklogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 在隔离Broker和Redis验证双组采集与失败独立性。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeadLetterBacklogSamplingIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private RabbitAdmin admin;
    @Autowired private CachingConnectionFactory factory;
    @Autowired private StringRedisTemplate redis;
    private final String namespace = "it-" + UUID.randomUUID();

    /**
     * 显式启用独立RabbitMQ虚拟主机。
     * @param registry 测试属性注册器
     */
    @DynamicPropertySource static void messaging(DynamicPropertyRegistry registry) {
        RabbitIntegrationSupport.connectionProperties().forEach((key, value) -> registry.add(key, () -> value));
    }

    /** 只清理本用例创建的隔离命名空间与拓扑。 */
    @AfterEach void cleanup() {
        try { RabbitIntegrationSupport.clearTopology(admin); }
        finally {
            String prefix = namespace + ":{deadletter-backlog}:";
            redis.delete(List.of(prefix + "generation", prefix + "erp_audit", prefix + "cache_invalidation"));
        }
    }

    /**
     * 真实双队列采样后删除一组队列，缺失组不可用而另一组仍可读。
     * @throws Exception Broker通道失败或采样中断
     */
    @Test void samplesIndependentGroupsFromRealBroker() throws Exception {
        var store = new RedisDeadLetterBacklogStore(redis, namespace);
        var service = new DeadLetterBacklogSamplingService(new RabbitDeadLetterBacklogProbe(admin), store, Duration.ofSeconds(30));
        try (var channel = factory.createConnection().createChannel(false)) {
            channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, null, "erp".getBytes(StandardCharsets.UTF_8));
            channel.basicPublish(DEAD_EXCHANGE, CACHE_QUEUE, null, "cache".getBytes(StandardCharsets.UTF_8));
            var probe = new RabbitDeadLetterBacklogProbe(admin);
            await().atMost(Duration.ofSeconds(5)).until(() -> probe.ready(Group.ERP_AUDIT).orElse(-1) == 1
                    && probe.ready(Group.CACHE_INVALIDATION).orElse(-1) == 1);
            assertTrue(service.sample());
            assertEquals(1, store.read(Group.ERP_AUDIT).orElseThrow());
            assertEquals(1, store.read(Group.CACHE_INVALIDATION).orElseThrow());
            assertTrue(admin.deleteQueue(ERP_QUEUE + ".dlq"));
            assertTrue(service.sample());
            assertTrue(store.read(Group.ERP_AUDIT).isEmpty());
            assertEquals(1, store.read(Group.CACHE_INVALIDATION).orElseThrow());
            admin.initialize();
        }
    }
}
