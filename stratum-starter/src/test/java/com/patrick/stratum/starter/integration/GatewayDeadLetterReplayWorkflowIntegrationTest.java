package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.patrick.stratum.application.event.OutboxConsumerApplicationService;
import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.job.ProtectedJobWorker;
import com.patrick.stratum.query.QueryGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.awaitility.Awaitility.await;

/** 复用真实重放工作流，验证独立网关入口与任务、消息和查询日志关联。 */
class GatewayDeadLetterReplayWorkflowIntegrationTest extends DeadLetterReplayWorkflowIntegrationTest {
    @LocalServerPort private int upstreamPort;
    private final StringRedisTemplate redis;
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final List<Logger> loggers = List.of(
            (Logger) LoggerFactory.getLogger(ProtectedJobWorker.class),
            (Logger) LoggerFactory.getLogger(OutboxRelayApplicationService.class),
            (Logger) LoggerFactory.getLogger(OutboxConsumerApplicationService.class),
            (Logger) LoggerFactory.getLogger(QueryGateway.class));
    private GatewayProcessFixture gateway;

    /**
     * 注入网关限流测试所需的独立 Redis 客户端。
     * @param redis 当前测试的 Redis 客户端
     */
    @Autowired
    GatewayDeadLetterReplayWorkflowIntegrationTest(StringRedisTemplate redis) { this.redis = redis; }

    /**
     * 捕获本次应用日志并启动连接真实 Starter 的独立网关。
     * @throws Exception 网关启动失败
     */
    @BeforeEach
    void startGateway() throws Exception {
        captured.start(); loggers.forEach(logger -> logger.addAppender(captured));
        gateway = GatewayProcessFixture.start(upstreamPort, redis);
    }

    /**
     * 核验网关、任务、原事件发布消费及状态查询，并退出本次网关进程。
     * @throws Exception 网关日志读取或退出失败
     */
    @AfterEach
    void stopGateway() throws Exception {
        try {
            if (gateway == null) return;
            await().atMost(Duration.ofSeconds(10)).until(() -> gateway.hasTrace("workflow-http")
                    && gateway.hasTrace("administration-http")
                    && contains(ProtectedJobWorker.class, "jobCode=outbox.dead-letter-replay", "traceId=workflow-http", "result=SUCCEEDED")
                    && contains(OutboxRelayApplicationService.class, "traceId=original-replay-trace", "result=CONFIRMED")
                    && contains(OutboxConsumerApplicationService.class, "traceId=original-replay-trace", "outcome=CONSUMED")
                    && contains(QueryGateway.class, "queryCode=outbox.replay-task.status", "traceId=administration-http", "result=success"));
        } finally {
            loggers.forEach(logger -> logger.detachAppender(captured)); captured.stop();
            if (gateway != null) gateway.close();
        }
    }

    /**
     * 在本次捕获的日志中查找指定组件和全部固定字段。
     * @param source 产生日志的组件
     * @param parts 必须同时出现的固定片段
     * @return 是否找到匹配的实际日志
     */
    private boolean contains(Class<?> source, String... parts) {
        synchronized (captured) {
            return captured.list.stream().filter(event -> event.getLoggerName().equals(source.getName()))
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> java.util.Arrays.stream(parts).allMatch(message::contains));
        }
    }

    /** {@inheritDoc} */
    @Override protected URI endpoint(String path) { return gateway.endpoint(path); }
}
