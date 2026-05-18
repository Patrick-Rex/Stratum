---
name: outbox-workflow
description: Stratum Outbox 与工作单元技能。当实现写模型事务、领域事件落盘、Relay 投递、重试、幂等与最终一致性链路时启用。
---

# Stratum Outbox 工作流技能

## 何时启用

- 新增跨边界事件发布
- 实现或调整 `Unit of Work`、事务提交链路
- 接入 Outbox 表、Relay、RabbitMQ 发布确认与重试
- 处理最终一致性、消费幂等、补偿和回放

## 先读这些规范

- docs/CONSTITUTION.md
- docs/FORBIDDEN.md
- docs/工作单元与事务一致性设计.md
- docs/消息总线设计.md
- docs/技术架构.md
- docs/可观测性设计.md

## Stratum 固定流程

1. 加载聚合
2. 执行业务规则
3. 持久化聚合
4. 写入 Outbox
5. 提交事务
6. 提交后触发非事务性后置动作

## Stratum 专属约束

- 工作单元只能由 Application 层驱动；Domain 与 Infrastructure 不得决定事务边界。
- 事务内业务数据与 Outbox 必须同事务提交，失败时整体回滚。
- 禁止直接发布 MQ 消息，必须经 Outbox Relay 异步投递到 RabbitMQ Topic。
- 消息体至少包含 `eventId、eventType、occurredAt、aggregateId、payload、traceId`。
- 发送确认失败必须重试并记录；消费端必须手动 ack 且具备幂等。
- 同一 `aggregateId` 的事件必须保证顺序消费；跨聚合流程依赖补偿或状态机。

## 命名约定

- exchange：`stratum.topic`
- routing key：`业务域.聚合.事件`
- queue：`q.业务域.事件`
- retry queue：`q.业务域.事件.retry`
- dlq：`q.业务域.事件.dlq`

## 实现步骤

1. 在 Application 用例中明确事务边界与聚合变更点。
2. 聚合产生领域事件后，将事件映射为 Outbox 记录并同事务落盘。
3. 由 Relay 扫描未投递记录，发布到 RabbitMQ，并处理 confirm 结果。
4. 对失败发送执行重试、状态流转与可观测记录。
5. 消费端以业务幂等键或事件 ID 去重，并在成功后手动 ack。
6. 为重试超限消息进入 DLQ 预留演练与回放流程。

## 审查清单

- 事务失败时业务表与 Outbox 表是否一起回滚？
- 是否有任何地方绕过 Outbox 直接发送 MQ？
- Relay 是否区分待发送、发送中、成功、失败、重试超限等状态？
- 消费端重复投递时是否会产生副作用？
- 日志与指标中是否补齐 `eventId、messageId、traceId、consumerGroup`？

## 常见反例

- 在 Domain 中直接操作 MQ 客户端。
- 业务数据提交成功，但事件通过另一个事务单独落盘。
- 消费失败后不 ack 也不重试规划，导致消息堆积不可控。
- 只保证发布成功，不保证消费幂等。

## 验证建议

- `gradlew :stratum-application:build :stratum-infrastructure:build`
- `gradlew :stratum-application:test :stratum-infrastructure:test`
- 针对目标链路补 4 类验证：事务回滚、Relay 重试、重复消费、DLQ 演练
- 文本检查：确认代码中不存在绕过 Outbox 的直接 MQ 发送路径