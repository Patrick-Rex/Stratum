package com.patrick.stratum.starter.integration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.rabbit.core.RabbitAdmin;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;

/** 统一真实Broker测试的隔离空间检查与固定拓扑清理，不保存客户端或凭据状态。 */
final class RabbitIntegrationSupport {
    /** 工具类不可实例化。 */
    private RabbitIntegrationSupport() { }

    /**
     * 强制本机随机专用虚拟主机与同名账号，缺失配置直接失败。
     * @return 每次新建、可由具体用例覆盖参数的配置
     */
    static Map<String, Object> connectionProperties() {
        String vhost = required("STRATUM_IT_MQ_VHOST");
        String username = required("STRATUM_IT_MQ_USERNAME");
        if (!vhost.matches("stratum-it-[a-f0-9]{32}") || !username.equals(vhost)) {
            throw new IllegalStateException("消息集成测试必须使用独立stratum-it虚拟主机及同名账号");
        }
        var values = new HashMap<String, Object>();
        values.put("stratum.messaging.enabled", "true");
        values.put("stratum.messaging.host", "127.0.0.1");
        values.put("stratum.messaging.port", required("STRATUM_IT_MQ_PORT"));
        values.put("stratum.messaging.virtual-host", vhost);
        values.put("stratum.messaging.username", username);
        values.put("stratum.messaging.password", required("STRATUM_IT_MQ_PASSWORD"));
        return values;
    }

    /**
     * 删除已经通过隔离检查的当前测试虚拟主机中的固定拓扑。
     * @param admin 绑定当前专用虚拟主机的管理员
     */
    static void clearTopology(RabbitAdmin admin) {
        for (String queue : List.of(CACHE_QUEUE, ERP_QUEUE, CACHE_QUEUE + ".dlq", ERP_QUEUE + ".dlq")) {
            admin.deleteQueue(queue);
        }
        admin.deleteExchange(EXCHANGE);
        admin.deleteExchange(DEAD_EXCHANGE);
    }

    /**
     * 获取必需测试配置，异常不包含配置原文。
     * @param name 环境变量名
     * @return 非空配置
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少消息集成测试配置：" + name);
        return value;
    }
}
