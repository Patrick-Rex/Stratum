# Stratum 术语表（GLOSSARY）

> **用途：** 防止 AI 对同一概念产生不同命名导致上下文腐烂。
> **规则：** 所有文档、代码、接口命名必须与本表保持一致。出现歧义时，本表优先。

---

## 1. 架构层术语

| 术语 | 定义 | 禁止混淆项 |
|------|------|------------|
| **聚合（Aggregate）** | Domain 层的一致性边界单元，包含聚合根与从属实体 | 不得称为 Model、Entity、Pojo |
| **聚合根（AggregateRoot）** | 聚合对外的唯一访问入口 | 不得直接暴露内部实体 |
| **仓储（Repository）** | Domain 层声明的持久化接口，Infrastructure 实现 | 不得称为 Dao、Mapper |
| **应用服务（ApplicationService）** | Application 层的用例编排类，负责事务边界 | 不得称为 Manager、Handler（Handler 留给 Query） |
| **领域服务（DomainService）** | 跨聚合的 Domain 层业务逻辑，无状态 | 不得将业务规则放入 ApplicationService |
| **查询处理器（QueryHandler）** | 实现 QueryGateway 的读模型查询单元 | 不得在 QueryHandler 中执行写操作 |
| **查询规格（QuerySpec）** | 封装过滤、排序、分页参数的不可变对象 | 不得将 QuerySpec 用于写用例 |
| **命令（Command）** | Application 层写用例的输入 DTO | 不得称为 Request（Request 是 Interface 层的 HTTP 入参） |
| **领域事件（DomainEvent）** | 聚合内部行为产生的事件，领域层声明 | 不得与集成事件混淆 |
| **集成事件（IntegrationEvent）** | 跨界的 MQ 消息，通过 Outbox 投递 | 不得在 Domain 层发布集成事件 |
| **Outbox** | 保证消息与数据库事务一致性的中继表机制 | 不得直接发 MQ（绕过 Outbox） |
| **工作单元（UnitOfWork）** | 协调多仓储的事务一致性边界（Application 层） | 不得在 Infrastructure 层管理工作单元 |

---

## 2. 模块术语

| 术语 | 指代 | 说明 |
|------|------|------|
| **Interface 层** | `stratum-interface` | HTTP 协议适配、参数校验、响应封装 |
| **Query 层** | `stratum-query` | 读模型查询，不执行写操作 |
| **Application 层** | `stratum-application` | 用例编排、事务边界、事件暴发 |
| **Domain 层** | `stratum-domain` | 聚合、仓储接口、领域规则 |
| **Infrastructure 层** | `stratum-infrastructure` | 仓储实现、缓存、MQ、配置 |
| **Job 层** | `stratum-job` | 后台任务调度与执行 |
| **Common 模块** | `stratum-common` | 共享工具、枚举、常量，不含业务 |
| **Starter 模块** | `stratum-starter` | 装配启动，不含业务逻辑 |
| **Gateway 层** | `stratum-gateway` | Nginx 统一入口、路由、限流 |

---

## 3. 接口术语

| 术语 | 定义 | 位置 |
|------|------|------|
| **Request** | HTTP 接口的入参 DTO | Interface 层 |
| **Response** | HTTP 接口的出参封装（统一 ApiResponse<T>） | Interface 层 |
| **Command** | 写用例的输入，由 Request 转换 | Application 层 |
| **QuerySpec** | 读用例的过滤规格对象 | Query 层 |
| **QueryResult<T>** | 统一的分页查询结果 | Query 层 |
| **DTO** | Application 或 Query 层的数据传输对象 | Application / Query 层 |
| **VO（ValueObject）** | Domain 层的值对象，不可变，无 ID | Domain 层 |

---

## 4. 可观测性术语

| 术语 | 定义 |
|------|------|
| **traceId** | 跨服务链路唯一追踪 ID，随请求透传 |
| **spanId** | 单次调用的追踪片段 ID |
| **结构化日志** | JSON 格式日志，包含 traceId、spanId、level、message、timestamp |
| **核心指标（Metrics）** | Prometheus 格式指标，暴露于 `/actuator/prometheus` |
| **告警规则（Alert）** | Grafana/Prometheus AlertManager 定义的阈值规则 |

---

## 5. 安全术语

| 术语 | 定义 |
|------|------|
| **Subject** | 认证后的当前用户主体（含 userId、roles、orgId） |
| **RBAC** | 基于角色的访问控制（Role-Based Access Control） |
| **数据权限（DataScope）** | 行级数据过滤规则，基于 orgId 或自定义范围 |
| **Token 撤销（Revocation）** | 将 JWT 加入黑名单（Redis）使其提前失效 |
| **RS256** | 非对称签名算法，JWT 签发方保留私钥，验证方使用公钥 |

## 6. 数据库术语
| 术语 | 定义 |
|------|------|
| **Outbox 表** | 保证事务一致性的中继表，必须与业务数据同一事务提交 |
| **幂等性键（Idempotency Key）** | 消费端去重的唯一键，必须用数据库唯一约束 |
| **死信队列（DLQ）** | 消费失败超过阈值后的队列，需人工介入处理 |
| **快照隔离（Snapshot Isolation）** | 推荐默认事务隔离级别 |

## 7. 消息队列术语
| 术语 | 定义 |
|------|------|
| **Topic** | RabbitMQ exchange，命名：stratum.topic |
| **Routing Key** | 消息路由规则，格式：domain.aggregate.event |
| **消费幂等** | 必须用 messageId + 数据库唯一约束实现 |


---

_当前版本：v1.0 | 建立日期：2026-03-31_
