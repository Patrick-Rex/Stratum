# AI可执行开发计划

## 1. 目标

本计划将 Stratum 的规范映射为 AI 可长期执行的开发节点图。
原则：节点小、依赖清晰、输出明确、验证可执行。

## 1.1 当前仓库起点

- 当前仓库已建立 9 个 Gradle 子模块；[build.gradle](../build.gradle) 为聚合根，[settings.gradle](../settings.gradle) 统一注册模块。
- Phase A 与 NODE-B01 的完成状态及历史验收见 [CONTEXT](CONTEXT.md)；后续从当前待执行节点接续，不重新执行已完成节点。
- 本文保留完整节点定义用于追溯与后续实施，不维护第二份完成状态清单。命令及当前环境基线见 [开发与迭代](development.md)。
- 涉及大量空目录初始化时，应优先提交最小必要构建文件、占位资源与可执行验证脚本，避免单节点因样板文件过多失控。
- 每个节点的 validation 应尽量落到可自动执行的检查，如 `gradlew projects`、`gradlew build`、`bootRun`、`/actuator/health`、`docker compose config`。

## 2. 节点编排规则

- 单节点只做一类变更。
- 单节点不跨越超过 4 个 spec。
- 单节点产出控制在 3 到 8 个文件内。
- 单节点必须能独立验证，通过后再进入下一节点。
- 对重复逻辑应优先沉淀到 common 扩展类、帮助类、基础类或基础枚举，再由业务代码复用，避免散落实现。

## 3. 阶段划分

### Phase A: 基础骨架

#### NODE-A01

- objective: 将根工程重构为 Gradle 多模块聚合骨架
- inputSpecs: SPEC-001, SPEC-002
- prerequisites: 无
- actions:
  - 将根 build.gradle 从单模块应用改为聚合根配置
  - 在 settings.gradle 中注册 gateway/interface/query/application/domain/infrastructure/job/common/starter 模块
  - 创建模块目录骨架与最小占位文件
- outputs:
  - 根聚合构建脚本
  - settings 模块声明
  - 模块目录骨架
- validation:
  - `gradlew projects` 可识别所有模块
  - 根工程不再承载应用启动职责与业务依赖
- handoff: 交给 NODE-A02

#### NODE-A02

- objective: 建立统一构建约束与 starter 启动骨架
- inputSpecs: SPEC-001, SPEC-002, SPEC-003
- prerequisites: NODE-A01
- actions:
  - 建立共享依赖约束与子模块统一构建策略
  - 创建 starter 启动入口
  - 接入 profiles 与 Nacos 配置占位
  - 暴露 actuator 基础端点
- outputs:
  - 共享构建约束
  - 启动类
  - application 配置骨架
  - actuator 基础配置
- validation:
  - `gradlew build` 可通过
  - starter 应用可本地启动
  - health 端点可访问
- handoff: 交给 NODE-A03

#### NODE-A03

- objective: 建立公共基础模块
- inputSpecs: SPEC-002, SPEC-005, SPEC-011
- prerequisites: NODE-A01
- actions:
  - 定义 ApiResponse
  - 定义统一错误码结构
  - 定义 traceId 上下文工具
- outputs:
  - common 基础类
  - 响应与异常模型
- validation:
  - common 模块可被其他模块编译依赖
  - ApiResponse、错误码与 traceId 工具可独立通过编译
- handoff: 交给 NODE-A04

#### NODE-A04

- objective: 建立统一异常处理与多语言消息基础设施
- inputSpecs: SPEC-005, SPEC-011
- prerequisites: NODE-A03
- actions:
  - 定义全局异常基类与异常映射策略
  - 建立错误码到 i18n 消息键的映射约定
  - 接入 MessageSource 与 locale 解析策略（Header/默认语言）
  - 在统一响应模型中输出本地化错误消息
- outputs:
  - 全局异常处理骨架
  - i18n 资源占位与消息键规范
  - 错误码本地化映射规则
- validation:
  - 同一错误码在不同 locale 下返回不同文案
  - 未知异常可被统一兜底并输出 traceId
- handoff: 交给 NODE-A05

#### NODE-A05

- objective: 建立基础扩展类与帮助类复用基线
- inputSpecs: SPEC-002, SPEC-005, SPEC-011
- prerequisites: NODE-A03
- actions:
  - 在 common 中建立 extension/helper/base/enums 目录与命名约定
  - 提供字符串、集合、时间、分页、校验等最小通用工具
  - 定义禁止在业务模块重复实现同类基础逻辑的约束
  - 补充示例调用，确保 application/interface/query 至少各有一个复用点
- outputs:
  - 扩展类与帮助类骨架
  - 基础枚举与基础类骨架
  - 复用约束说明
- validation:
  - 下游模块可直接依赖并调用 common 扩展能力
  - 抽样检查无重复基础逻辑实现
- handoff: 交给 Phase B

### Phase B: 写模型骨架

#### NODE-B01

- objective: 建立 Domain 核心抽象
- inputSpecs: SPEC-001, SPEC-002, SPEC-007
- prerequisites: NODE-A01
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[技术架构.md](技术架构.md)、[工作单元与事务一致性设计.md](工作单元与事务一致性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - Domain 不得引入框架依赖
  - 不进入 NODE-B02
- actions:
  - 定义 AggregateRoot、Entity、ValueObject 基类
  - 定义 Repository 接口基类
  - 定义 DomainEvent 基类
- outputs:
  - domain 基础抽象
- validation:
  - domain 模块无框架依赖
- handoff: 交给 NODE-B02

#### NODE-B02

- objective: 建立 Application 写用例基架
- inputSpecs: SPEC-002, SPEC-007, SPEC-011
- prerequisites: NODE-B01, NODE-A03, NODE-A05
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[项目结构.md](项目结构.md)、[工作单元与事务一致性设计.md](工作单元与事务一致性设计.md)、[横切关注点与AOP设计.md](横切关注点与AOP设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不实现具体业务用例
  - 不进入 NODE-B03
- actions:
  - 定义 Command、CommandHandler、UseCaseResult
  - 定义事务边界与 UoW 门面接口
  - 预留 OperationLog 注解落点
- outputs:
  - application 写模型基架
- validation:
  - 写用例可通过统一入口执行
- handoff: 交给 NODE-B03

#### NODE-B03

- objective: 建立 Infrastructure 持久化骨架
- inputSpecs: SPEC-003, SPEC-007
- prerequisites: NODE-A02, NODE-B01, NODE-B02
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[基础设施配置.md](基础设施配置.md)、[工作单元与事务一致性设计.md](工作单元与事务一致性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-B04
  - 不实现消息和任务能力
- actions:
  - 接入 MariaDB、Flyway、Easy-Query
  - 接入 Redis、Caffeine 基础客户端与配置装配
  - 在 Application 落地 UoW 事务适配，Infrastructure 提供持久化实现，不反向依赖 Application
  - 定义基础仓储实现模板
- outputs:
  - 数据源配置
  - migration 目录
  - repository 模板
- validation:
  - 应用可连通数据库并执行迁移
- handoff: 交给 NODE-B04

#### NODE-B04

- objective: 建立 Outbox 与事务一致性链路
- inputSpecs: SPEC-003, SPEC-007, SPEC-008
- prerequisites: NODE-B03, NODE-B02
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[工作单元与事务一致性设计.md](工作单元与事务一致性设计.md)、[消息总线设计.md](消息总线设计.md)、[基础设施配置.md](基础设施配置.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 Phase C
  - 不实现 RabbitMQ 消费者
- actions:
  - 创建 outbox_event 表与实体
  - 在 UoW 提交内写入 outbox
  - 实现 Outbox 状态流转模型
- outputs:
  - outbox 持久化实现
  - 事务内事件落盘逻辑
- validation:
  - 事务失败时业务数据与 outbox 一起回滚
- handoff: 交给 NODE-B05

#### NODE-B05

- objective: 建立缓存治理与分布式并发控制基建
- inputSpecs: SPEC-003, SPEC-011
- prerequisites: NODE-B03, NODE-A05
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[基础设施配置.md](基础设施配置.md)、[横切关注点与AOP设计.md](横切关注点与AOP设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 Phase C
  - 不实现具体业务缓存
  - 不在业务模块重复实现缓存等待与分布式锁逻辑
- actions:
  - 在 infrastructure/common 建立缓存访问门面与 single-flight 本地合并机制
  - 建立 Redis 分布式锁组件（lockTTL=30 秒，acquireWaitTimeout=5 秒）
  - 落地缓存防护策略：空值缓存（TTL=60 秒）、热点 key 互斥重建、TTL 抖动
  - 固化旁路更新缓存与补偿任务约定（更新失败降级删缓存并记录补偿事件）
  - 输出缓存与锁的埋点指标约定，供 query/application/job 复用
- outputs:
  - 缓存治理组件
  - 分布式锁组件
  - 缓存键与锁键规范
  - 埋点与补偿约定
- validation:
  - 热点 key 并发重建时仅单请求回源
  - 锁竞争等待超时行为可复现且输出 traceId
  - 下游模块可复用统一缓存与锁组件，无重复实现
- handoff: 交给 Phase C

### Phase C: 读模型与 API

#### NODE-C01

- objective: 建立 Query 基架
- inputSpecs: SPEC-006, SPEC-005
- prerequisites: NODE-A03, NODE-B02, NODE-B05
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[查询基架设计.md](查询基架设计.md)、[API设计规范.md](API设计规范.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-C02
  - 不实现具体业务查询
- actions:
  - 定义 QueryGateway、QueryHandler、QuerySpec、QueryResult
  - 定义分页排序白名单机制
  - 定义查询上下文接口
- outputs:
  - query 模块基架
- validation:
  - 查询可统一路由到 handler
- handoff: 交给 NODE-C02

#### NODE-C02

- objective: 建立 Interface API 基架
- inputSpecs: SPEC-005, SPEC-011, SPEC-012
- prerequisites: NODE-A02, NODE-A03, NODE-A04, NODE-A05, NODE-B02, NODE-C01
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[API设计规范.md](API设计规范.md)、[横切关注点与AOP设计.md](横切关注点与AOP设计.md)、[可观测性设计.md](可观测性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-C03
  - 不实现 auth 和网关
- actions:
  - 建立 Controller、参数校验、异常映射
  - 接入统一响应包装与 traceId 回传
  - 接入 API 版本头处理
  - 接入 OpenAPI 文档生成与 Knife4j UI 展示
- outputs:
  - interface 模块 API 基础设施
  - OpenAPI 文档端点与 Knife4j UI 访问入口
- validation:
  - 返回统一响应结构
  - traceId 出现在响应体中
  - OpenAPI JSON 可访问且 Knife4j 页面可正常展示接口文档
- handoff: 交给 NODE-C03

#### NODE-C03

- objective: 建立一个端到端示例能力
- inputSpecs: SPEC-002, SPEC-005, SPEC-006, SPEC-007
- prerequisites: NODE-A02, NODE-B05, NODE-C02
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[项目结构.md](项目结构.md)、[API设计规范.md](API设计规范.md)、[查询基架设计.md](查询基架设计.md)、[工作单元与事务一致性设计.md](工作单元与事务一致性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 Phase D
  - 不实现权限与网关
- actions:
  - 选择一个简单领域对象
  - 打通创建、查询、详情接口
  - 验证写模型与读模型协作
- outputs:
  - 一个完整样例能力
- validation:
  - CRUD 最小闭环可运行
- handoff: 交给 Phase D

### Phase D: 安全与网关

#### NODE-D01

- objective: 建立 JWT 认证基架
- inputSpecs: SPEC-004, SPEC-005
- prerequisites: NODE-A02, NODE-C02
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[安全认证设计.md](安全认证设计.md)、[API设计规范.md](API设计规范.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-D02
  - 不实现 RBAC 细节
- actions:
  - 接入 Spring Security
  - 定义 access/refresh token 结构
  - 定义 auth 接口骨架
- outputs:
  - 安全过滤链
  - 认证接口骨架
- validation:
  - 未认证请求返回 401xx
- handoff: 交给 NODE-D02

#### NODE-D02

- objective: 建立 token 撤销与 RBAC
- inputSpecs: SPEC-004, SPEC-003
- prerequisites: NODE-D01
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[安全认证设计.md](安全认证设计.md)、[基础设施配置.md](基础设施配置.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-D03
  - 不实现数据权限过滤
- actions:
  - 建立 refresh 白名单与 user ver 校验
  - 建立角色权限模型
  - 实现方法级鉴权
- outputs:
  - 权限模型与 Redis 键接入
- validation:
  - logout-all 使历史 access 立即失效
- handoff: 交给 NODE-D03

#### NODE-D03

- objective: 建立数据权限与查询联动
- inputSpecs: SPEC-004, SPEC-006
- prerequisites: NODE-D02, NODE-C01
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[安全认证设计.md](安全认证设计.md)、[查询基架设计.md](查询基架设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-D04
  - 不调整网关配置
- actions:
  - 定义数据范围上下文
  - 在查询入口附加数据范围过滤
  - 在写用例前做目标对象范围校验
- outputs:
  - 数据权限过滤链
- validation:
  - 非授权数据无法读写
- handoff: 交给 NODE-D04

#### NODE-D04

- objective: 建立网关配置与项目外负载均衡对接约束
- inputSpecs: SPEC-010, SPEC-013, SPEC-012
- prerequisites: NODE-A02, NODE-C02
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[网关与负载均衡设计.md](网关与负载均衡设计.md)、[容器化与部署.md](容器化与部署.md)、[可观测性设计.md](可观测性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 Phase E
  - 不改业务接口逻辑
- actions:
  - 在 `stratum-gateway` 建立 Spring Boot Gateway 路由、限流、Header 灰度与 trace 透传配置
  - 定义项目外负载均衡对接约定（裸金属 Nginx 或容器编排层 Ingress），不在项目内实现 upstream
  - 输出网关访问日志与 `/actuator/prometheus` 指标
- outputs:
  - gateway 配置文件
  - compose 中的 gateway 服务
  - 项目外负载均衡对接说明
- validation:
  - 经网关访问应用成功
  - 关闭一个 app 副本后由项目外负载均衡自动切换
- handoff: 交给 Phase E

### Phase E: 消息与任务

#### NODE-E01

- objective: 建立 RabbitMQ Topic 总线
- inputSpecs: SPEC-008, SPEC-003
- prerequisites: NODE-A02, NODE-B04
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[消息总线设计.md](消息总线设计.md)、[基础设施配置.md](基础设施配置.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-E02
  - 不实现 Outbox Relay
- actions:
  - 建立 exchange、queue、retry、dlq 配置
  - 建立消息发布器
  - 建立消息头 trace 透传
- outputs:
  - 消息总线基础设施
- validation:
  - 消息可发送到目标队列
- handoff: 交给 NODE-E02

#### NODE-E02

- objective: 建立 Outbox Relay 与消费幂等
- inputSpecs: SPEC-008, SPEC-007, SPEC-012
- prerequisites: NODE-E01, NODE-B04
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[消息总线设计.md](消息总线设计.md)、[工作单元与事务一致性设计.md](工作单元与事务一致性设计.md)、[可观测性设计.md](可观测性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-E03
  - 不做后台任务 API
- actions:
  - 实现 Relay 扫描投递
  - 实现消费者 messageId 幂等去重
  - 建立失败重试与 DLQ 流程
- outputs:
  - Relay 任务
  - 消费幂等组件
- validation:
  - 重复消息不产生副作用
  - 失败消息进入 DLQ
- handoff: 交给 NODE-E03

#### NODE-E03

- objective: 建立后台任务调度体系
- inputSpecs: SPEC-009, SPEC-003, SPEC-012
- prerequisites: NODE-A02, NODE-B02
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[后台任务设计.md](后台任务设计.md)、[基础设施配置.md](基础设施配置.md)、[可观测性设计.md](可观测性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-E04
  - 不实现任务业务逻辑
- actions:
  - 接入 Quartz 与数据库 JobStore
  - 接入 ShedLock
  - 建立任务执行日志与 dead_job 记录
- outputs:
  - job 模块基础设施
- validation:
  - 双实例部署下同一任务不重复执行
- handoff: 交给 NODE-E04

#### NODE-E04

- objective: 建立异步任务 API 闭环
- inputSpecs: SPEC-005, SPEC-009
- prerequisites: NODE-E03, NODE-C02
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[API设计规范.md](API设计规范.md)、[后台任务设计.md](后台任务设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 Phase F
  - 不改消息总线实现
- actions:
  - 提供任务触发、查询、取消接口
  - 返回 taskId、status、progress、traceId
  - 打通执行状态回写
- outputs:
  - 后台任务接口
- validation:
  - 任务状态可查询
  - 取消接口幂等
- handoff: 交给 Phase F

### Phase F: 可观测性与收敛

#### NODE-F01

- objective: 建立 trace、日志、指标基础能力
- inputSpecs: SPEC-011, SPEC-012
- prerequisites: NODE-A02, NODE-C02
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[横切关注点与AOP设计.md](横切关注点与AOP设计.md)、[可观测性设计.md](可观测性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-F02
  - 不改网关与任务逻辑
- actions:
  - 接入 OTel
  - 输出结构化日志
  - 暴露 Prometheus 指标
- outputs:
  - trace/log/metrics 基础能力
- validation:
  - traceId 可贯通 HTTP 链路
- handoff: 交给 NODE-F02

#### NODE-F02

- objective: 扩展观测到网关、消息、任务、查询
- inputSpecs: SPEC-012, SPEC-008, SPEC-009, SPEC-010
- prerequisites: NODE-D04, NODE-E02, NODE-E04, NODE-F01
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[可观测性设计.md](可观测性设计.md)、[网关与负载均衡设计.md](网关与负载均衡设计.md)、[消息总线设计.md](消息总线设计.md)、[后台任务设计.md](后台任务设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-F03
  - 不改业务接口
- actions:
  - 建立网关日志关联
  - 建立消息发布消费指标
  - 建立任务与查询慢日志指标
- outputs:
  - 统一观测面板输入数据
- validation:
  - 可按 traceId 关联网关、应用、任务、消息日志
- handoff: 交给 NODE-F03

#### NODE-F03

- objective: 补齐 compose 与联调环境
- inputSpecs: SPEC-013, SPEC-012
- prerequisites: NODE-D04, NODE-E01, NODE-E03, NODE-F01
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[容器化与部署.md](容器化与部署.md)、[可观测性设计.md](可观测性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 NODE-F04
  - 不改领域模型
- actions:
  - 补齐 dev 与 observability compose
  - 配置 exporter、Prometheus、Grafana、Jaeger、Loki
  - 验证依赖联通
- outputs:
  - compose 与监控配置
- validation:
  - 全部依赖可启动并联通
- handoff: 交给 NODE-F04

#### NODE-F04

- objective: 形成最小可交付版本的验收与交接产物
- inputSpecs: SPEC-001, SPEC-005, SPEC-012, SPEC-013
- prerequisites: 所有前置节点
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[SDD执行总览.md](SDD执行总览.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不扩展新功能
  - 只做收敛与修复
  - 变更文件不超过 8 个，若超出先拆分子节点
- actions:
  - 汇总前置节点验收证据与遗留项
  - 只处理发布阻断级收口问题，不在本节点引入新的跨主题能力实现
  - 输出验收记录与回滚说明
- outputs:
  - v1.0 最小可交付基线
- validation:
  - 前置节点全部完成且验收证据齐全
  - DoD 全部满足
- handoff: 进入迭代功能开发

## 4. 顺序执行与交付门禁

本轮按用户 2026-10-23 授权，先调整计划，再从首个未完成节点依次执行至 NODE-F04 的非部署验收。用户已授权每个节点验收后提交并推送，无需逐次请求确认。

1. 以 CONTEXT 确认进度，从 NODE-B02 接续；不重复实现已完成节点。
2. 每次只实施一个节点，核对 prerequisites、inputSpecs、参考文档、本次范围与完成标准。节点中的“不进入下一节点”表示当前变更集不得混入下一节点工作；当前节点验收、提交与推送完成后继续下一节点。
3. 预计变更超过 8 个文件时，先在本计划登记可独立验证的子节点；文件数包含代码、测试、配置及同步文档。子节点也分别验证、提交和推送，父节点全部子节点验收后才标为完成。
4. 验证失败先修复当前节点，不以编译成功替代行为验证，不以空测试、模拟外部服务或未运行的验收项声明集成验收通过。
5. 验收后同步 CONTEXT 与 SESSION-HANDOFF，记录 completedNode、changedFiles、validations、unresolved、nextNode；按技能完成提交前审查。
6. 每节点（或子节点）独立创建 Conventional Commits 提交并推送当前分支。推送失败保留本地提交，先解决推送问题；不得跳过失败推送继续累积节点，不强推、不覆盖远端历史。
7. 外部依赖、凭据或业务规则无法核实时，明确交给用户；可继续与阻塞无关的准备，但不将节点标为完成。会话结束前关闭本次启动的应用、容器及服务。

### 本轮范围与顺序

- 执行顺序：NODE-B02 → B03 → B04 → B05 → C01 → C02 → C03 → D01 → D02 → D03 → D04.1 → E01 → E02 → E03 → E04 → F01 → F02 → F04.1。
- NODE-D04.1 实现应用内网关路由、限流、Header 灰度、trace 与指标并本地验证；NODE-D04.2 的 Compose 服务、项目外负载均衡配置及多副本故障切换部署验收暂缓。
- NODE-F03（Compose、观测服务编排及部署环境）整体暂缓；NODE-F04.1 汇总本轮应用能力和本地集成验收，NODE-F04.2 保留部署及回滚演练。暂缓项不记为已完成，也不宣称完整 v1.0 已交付。
- F02 的网关前置为 D04.1；F04.1 不依赖 D04.2、F03、F04.2。E03 的竞争保护可用本地多实例或独立调度器验证，不做生产部署。
- 可使用当前 Docker 环境做本地集成验证。既有容器和数据保留；新增测试资源使用项目专属名称和独立数据库，结束后仅清理本次创建的资源。
- 依赖采用与 JDK 25 / Spring Boot 4 兼容的稳定版本，接入前核对官方兼容说明、许可与实际构建；不机械套用旧计划中的产品版本。具体调整同步技术基线与相关规范。

### NODE-B02 子节点

| 子节点 | 范围 | 验证 | 前置 |
|--------|------|------|------|
| NODE-B02.1 | Command、CommandHandler、UseCaseResult、UnitOfWork 门面、OperationLog 注解契约 | application 编译、无 Infrastructure 反向依赖、契约签名检查 | NODE-B01、NODE-A03、NODE-A05 |
| NODE-B02.2 | 统一命令入口、最小测试依赖与路由/异常/工作单元调用测试 | 实际执行成功路由、重复注册、未知命令及异常传播测试 | NODE-B02.1 |

NODE-B02 仅建立可注入的工作单元契约与调用边界；真实数据库事务适配在 NODE-B03 落地，事务策略和 `@Transactional` 保持在 Application 层。其他节点在实现前按预计文件数补充子节点，不为文件数限制压缩职责或省略测试。


## 5. AI 执行提示词模板

执行任一节点时，输入最少包含：

- 当前 nodeId
- objective
- inputSpecs
- prerequisites 状态
- 目标输出文件
- validation 方法

若缺失其中任一项，先补计划，再执行实现。
