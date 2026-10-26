package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;

/** 完整Starter同时连接三类真实基础设施，验证消息装配与原有事务边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessagingApplicationIntegrationTest extends MariaDbIntegrationTest {
    private final UnitOfWork unitOfWork;
    private final OutboxRepository outbox;
    private final OutboxPublisher publisher;
    private final JdbcTemplate jdbc;
    private final RabbitAdmin admin;
    private final ApplicationContext context;
    private final String eventId = UUID.randomUUID().toString();
    @LocalServerPort private int port;

    /**
     * 注入真实应用端口与独立断言客户端。
     * @param unitOfWork 应用工作单元
     * @param outbox 同事务事件仓储
     * @param publisher 已装配发布端口
     * @param jdbc 数据库断言
     * @param admin 专用Broker管理员
     * @param context 应用装配查询
     */
    @Autowired
    MessagingApplicationIntegrationTest(UnitOfWork unitOfWork, OutboxRepository outbox, OutboxPublisher publisher,
            JdbcTemplate jdbc, RabbitAdmin admin, ApplicationContext context) {
        this.unitOfWork = unitOfWork; this.outbox = outbox; this.publisher = publisher;
        this.jdbc = jdbc; this.admin = admin; this.context = context;
    }

    /**
     * 只为本类启用专用消息空间，不改变其他ERP测试的缺省关闭状态。
     * @param registry Spring测试属性
     */
    @DynamicPropertySource
    static void configureMessaging(DynamicPropertyRegistry registry) {
        RabbitIntegrationSupport.connectionProperties().forEach((key, value) -> registry.add(key, () -> value));
    }

    /** 每用例重新声明固定拓扑，避免跨测试依赖队列存续。 */
    @BeforeEach
    void prepareTopology() { admin.initialize(); }

    /** 清理自己的Outbox探针及专用消息拓扑。 */
    @AfterEach
    void cleanup() {
        try { jdbc.update("DELETE FROM outbox_event WHERE event_id = ?", eventId); }
        finally { RabbitIntegrationSupport.clearTopology(admin); }
    }

    /**
     * 完整应用拥有唯一发布端口和可用健康入口，尚无Rabbit监听容器或自动Relay。
     * @throws Exception HTTP健康检查失败
     */
    @Test
    void startsWithEnabledMessagingAndHealthyExistingDependencies() throws Exception {
        assertEquals(1, context.getBeansOfType(OutboxPublisher.class).size());
        assertTrue(context.getBeansOfType(org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer.class).isEmpty());
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("UP", JsonMapper.builder().build().readTree(response.body()).path("status").asText());
        }
    }

    /** 已启用Broker不改变Outbox原子落盘，事务内禁止发送，回滚不产生消息或记录。 */
    @Test
    void keepsOutboxCommitAndRollbackSeparateFromPublishing() {
        var message = new OutboxMessage(eventId, "erp.probe.created", UUID.randomUUID().toString(),
                Instant.now(), "{\"probe\":true}", "messaging-app-trace");
        unitOfWork.execute(() -> { outbox.append(message); return null; });
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, eventId));
        assertEquals(0, admin.getQueueInfo(ERP_QUEUE).getMessageCount());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> publisher.publish(new OutboxPublisher.Delivery(message, 1))));
        jdbc.update("DELETE FROM outbox_event WHERE event_id = ?", eventId);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            outbox.append(message);
            throw new IllegalStateException("rollback-probe");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id = ?", Integer.class, eventId));
        assertEquals(0, admin.getQueueInfo(ERP_QUEUE).getMessageCount());
        assertEquals(0, admin.getQueueInfo(CACHE_QUEUE).getMessageCount());
    }
}
