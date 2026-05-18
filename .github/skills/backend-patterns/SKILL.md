---
name: backend-patterns
description: Stratum 后端分层与实现模式技能。当设计 DDD 单体链路、Application/Domain/Infrastructure 协作、Outbox、缓存、任务或可观测性时启用。
---

# Stratum 后端模式技能

## 何时启用

- 设计或重构 Application/Domain/Infrastructure 链路
- 新增仓储接口与实现
- 设计缓存、锁、Outbox、后台任务
- 定义异常处理、审计、追踪、限流等横切能力

## 先读这些规范

- docs/CONSTITUTION.md
- docs/FORBIDDEN.md
- docs/技术架构.md
- docs/项目结构.md
- docs/工作单元与事务一致性设计.md
- docs/消息总线设计.md
- docs/后台任务设计.md

## 模块落点速查

- `stratum-interface`：HTTP 协议适配、参数校验、响应封装
- `stratum-query`：只读查询、分页/排序/过滤、DTO 组装
- `stratum-application`：用例编排、事务边界、事件编排
- `stratum-domain`：聚合、实体、值对象、仓储接口、领域服务
- `stratum-infrastructure`：仓储实现、缓存、MQ、审计、配置
- `stratum-job`：Quartz/ShedLock 调度与执行
- `stratum-common`：跨模块基础扩展、帮助类、基础模型

## 核心模式

- 写链路固定为 `Interface -> Application -> Domain -> Repository Interface -> Infrastructure`。
- 读链路固定为 `Query Endpoint -> QueryGateway -> QueryHandler -> QueryResult/DTO`。
- 事务只能在 Application 层开启；Domain 与 Infrastructure 不得控制事务。
- 跨边界消息必须经 Outbox 落盘后再投递。
- 缓存、Redis 锁、MQ、Easy-Query 只能落在 Infrastructure 或其装配层。
- Job 只负责任务调度与调用 Application，用例逻辑不能写在 Job 中。
- `traceId`、统一异常、审计日志属于横切能力，优先复用已有公共基础设施。

## 决策检查

- 这个判断是业务规则还是技术编排？
- 这个依赖方向是否满足 `Interface -> Application -> Domain <- Infrastructure`？
- 这个复用能力能否先下沉到 `stratum-common`？
- 这个跨边界副作用是否需要 Outbox、重试、幂等与补偿？
- 这个定时任务是否已声明 ShedLock 与可观测输出？

## 常见反例

- Domain 导入 Spring、EasyQuery、Redis、RabbitMQ。
- Infrastructure 实现类命名为 `XxxService`。
- Query 模块直接写库或调用仓储实现类。
- 在 Job 中直接操作数据库或写业务决策。
- 直接发送 MQ，绕过 Outbox。

## 验证建议

- `gradlew :stratum-domain:build`
- `gradlew :stratum-application:build`
- `gradlew :stratum-infrastructure:build`
- `gradlew :stratum-job:build`
- 触及领域边界时，额外检查 Domain 源码中不存在框架导入与注解