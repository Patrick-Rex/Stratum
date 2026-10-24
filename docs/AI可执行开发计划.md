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

- objective: 建立通用进销存 ERP 端到端业务闭环
- inputSpecs: SPEC-002, SPEC-005, SPEC-006, SPEC-007
- prerequisites: NODE-A02, NODE-B05, NODE-C02
- referenceDocs: [AI可执行开发计划.md](AI可执行开发计划.md)、[Spec清单.md](Spec清单.md)、[项目结构.md](项目结构.md)、[API设计规范.md](API设计规范.md)、[查询基架设计.md](查询基架设计.md)、[工作单元与事务一致性设计.md](工作单元与事务一致性设计.md)
- scope: actions 所列内容为本节点“本次只做”范围。
- constraints:
  - 不进入 Phase D
  - 不实现权限与网关
- actions:
  - 建立物料、客户、供应商、仓库主数据
  - 打通采购收货入库、销售出库与库存余额/流水查询
  - 禁止负库存，支持小数数量与仓库维度；单据过账与库存变化同事务，重复过账不能重复增减库存
  - 验证写模型、查询基架、Outbox 与缓存协作；本节点不提前实现鉴权
  - 首期不加入财务记账、税务、批次序列号或生产 BOM
- outputs:
  - 通用进销存 ERP 主数据与采购、销售、库存业务 API
- validation:
  - 主数据 CRUD、采购收货、销售出库与库存查询可运行；库存不足与重复过账场景经过验证
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


### NODE-B03 子节点

| 子节点 | 范围 | 验证 | 前置 |
|--------|------|------|------|
| NODE-B03.1 | 统一 Spring Boot 依赖管理；引入 Boot 4 对应 Easy-Query、MariaDB、Flyway、Redis、Caffeine 与集成测试依赖 | 相关模块编译、依赖解析和许可核对 | NODE-B02 |
| NODE-B03.2a | 数据源与初始迁移、Application 事务适配及启动装配、运行文档 | 模块构建与真实 MariaDB 启动检查 | NODE-B03.1 |
| NODE-B03.2b | 工作单元真实数据库行为验证 | MariaDB 迁移、提交、回滚与嵌套事务测试 | NODE-B03.2a |
| NODE-B03.3a | 可运行 Easy-Query 仓储模板（测试夹具），不预建业务表 | 真实数据库 CRUD、分页与乐观锁竞争测试 | NODE-B03.2b |
| NODE-B03.3b | Redis/Caffeine 客户端配置与连通性验证 | 真实 Redis 带 TTL 读写、Caffeine 有界缓存测试 | NODE-B03.3a |

### NODE-B04 子节点

| 子节点 | 范围 | 验证 | 前置 |
|--------|------|------|------|
| NODE-B04.1 | Outbox 消息契约、状态及 Flyway 表；事件写入端口与基础映射 | 编译、迁移与模型约束验证 | NODE-B03 |
| NODE-B04.2 | Outbox 持久化适配与 Application 事件落盘入口 | 真实数据库验证业务写入与 Outbox 同提交/同回滚、重复事件拒绝、事务外拒写 | NODE-B04.1 |

Outbox Relay、MQ 发布和消费幂等仍在 Phase E 实现，B04 只保证事务内可靠落盘。

### NODE-B05 子节点

| 子节点 | 范围 | 验证 | 前置 |
|--------|------|------|------|
| NODE-B05.1 | Redis 分布式锁端口、唯一 token 与安全释放；配置默认 30 秒租期、5 秒等待上限 | 真实 Redis 竞争、过期、旧持有者不能释放新锁 | NODE-B04 |
| NODE-B05.2a | 版本化缓存契约、TTL 抖动、空值与有界 single-flight 重建核心 | 缓存失效、并发合并、空值 60 秒及边界单测 | NODE-B05.1 |
| NODE-B05.2b1 | 版本化 Redis 存储与失效屏障，接入 Caffeine L1 | 真实 Redis 版本比较、TTL、空值及旧版本拒写 | NODE-B05.2a |
| NODE-B05.2b2 | 缓存门面组合 single-flight 与分布式重建等待 | 多客户端竞争仅一次回源、超时不返回假空值、迟到回源不污染缓存 | NODE-B05.2b1 |
| NODE-B05.3a | 提交后缓存更新、失败删除与持久化补偿事件 | 数据库回滚不更新缓存、缓存故障不丢补偿 | NODE-B05.2b2 |
| NODE-B05.3b1 | 缓存访问、回源与 single-flight 指标及 trace 日志 | 命中/未命中、回源次数、合并与加载耗时指标可核对，错误不误报空值 | NODE-B05.3a |
| NODE-B05.3b2 | 分布式锁指标及 B05 验收汇总 | 锁竞争与等待指标、缓存/事务/补偿集成回归，汇总实际结果 | NODE-B05.3b1 |

补偿先以 Outbox 可靠落盘，Phase E 接入投递和执行；等待与后台资源必须有界且可关闭。实现时若仍超出 8 文件上限，继续拆分子节点，不压缩测试与职责。

### NODE-C01 子节点

| 子节点 | 范围 | 验证 | 前置 |
|--------|------|------|------|
| NODE-C01.1 | QuerySpec 分页复用、排序/过滤字段和操作白名单、类型化条件 | 分页默认/上限、非法排序和过滤拒绝、区间边界、输入不可变与注入字符串保留为参数 | NODE-B05 |
| NODE-C01.2 | Query、QueryHandler、QueryResult 与显式 QueryContext 契约 | 契约编译与结果边界测试，不将编译计为路由验收 | NODE-C01.1 |
| NODE-C01.3 | QueryGateway 类型路由、装配与查询观测入口 | 唯一路由、重复注册拒绝、上下文传递、异常传播与结果数量约束 | NODE-C01.2 |

本节点不提供业务查询；SQL 参数化在 ERP 查询适配中验证，鉴权数据范围由 D03 接入显式查询上下文，不能将查询上下文契约宣称为已实现权限控制。

### NODE-C02 子节点

| 子节点 | 范围 | 验证 | 前置 |
|--------|------|------|------|
| NODE-C02.1 | Servlet traceId 生命周期、响应头与 Web 依赖 | 合法/非法传入值、异常清理、线程上下文恢复及异步/错误再分派 | NODE-C01.3 |
| NODE-C02.2 | Spring MVC 版本头策略与专用版本错误码 | 缺省版本1、支持版本、未知/格式错误版本返回400 | NODE-C02.1 |
| NODE-C02.3a | ResponseBodyAdvice 统一业务 JSON 响应 | 对象/字符串/空值/已有包装、状态码、下载及外部控制器隔离 | NODE-C02.2 |
| NODE-C02.3b | Jakarta Validation 与 HTTP 异常映射 | 非法参数/JSON、业务异常、协议状态及内部错误脱敏 | NODE-C02.3a |
| NODE-C02.4a1 | 将MVC测试探针限定为显式测试装配 | MVC回归及完整应用事务集成测试，避免测试配置污染组件扫描 | NODE-C02.3b |
| NODE-C02.4a2 | 接入兼容 Boot 4 的 OpenAPI 与 Knife4j 静态展示依赖 | 完整应用HTTP启动、文档JSON、UI发现配置及页面静态资源访问 | NODE-C02.4a1 |
| NODE-C02.4b | 对齐生成文档与统一业务响应、版本和错误契约 | 测试业务接口的文档/运行响应一致，Knife4j展示生成文档，完成C02验收 | NODE-C02.4a2 |

每个子节点包含实现、行为验证及状态更新，总计不超过8个文件；必要时先继续细分。C02.1 提供请求关联标识，W3C/OTel 链路、请求日志和动态开关在 F01 完成，不将关联标识当作完整追踪实现。业务 Controller 随 C03 交付，C02 通过测试 Controller 验证基架。

### NODE-C03 子节点

C03 沿用 SPEC-002、SPEC-005、SPEC-006、SPEC-007。按以下顺序落实；每个实现子节点仍包含测试、CONTEXT 与 SESSION-HANDOFF，最多8个文件。后续分组在进入实现前继续登记具体子节点及输出，不以分组名称规避文件数限制。

| 子节点或分组 | 目标与输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-C03.1a | 幂等结果值对象、仓储端口、Easy-Query映射/适配、Flyway表及真实数据库测试 | 结果读写、作用域隔离、唯一约束、事务外拒写及重复写回滚 | NODE-C02 |
| NODE-C03.1b | Application幂等执行入口，重放已提交结果并校验请求指纹 | 顺序重复不再执行、同键不同请求冲突、失败回滚、并发唯一提交及重试重放 | NODE-C03.1a |
| NODE-C03.2（待细分） | 物料、客户、供应商、仓库主数据领域、仓储与写命令 | 主数据CRUD、乐观锁、引用保护及Outbox/缓存协作 | NODE-C03.1b |
| NODE-C03.3（待细分） | 按物料与仓库维护库存余额和不可重复的库存流水 | 小数入库/出库、禁止负库存、并发冲突与同事务回滚 | NODE-C03.2 |
| NODE-C03.4（待细分） | 采购、销售单据与收货/出库用例 | 已确认履约规则、重复过账、库存变化及Outbox原子性 | NODE-C03.3 |
| NODE-C03.5（待细分） | 主数据、单据、库存余额/流水的QueryGateway查询 | 分页/白名单/参数绑定与详情，不加入写事务 | NODE-C03.4 |
| NODE-C03.6（待细分） | HTTP协议适配、幂等头与OpenAPI业务文档 | 真实HTTP主数据与进销存闭环、错误与重复请求 | NODE-C03.5 |
| NODE-C03.7（待细分） | 完成C03集成验收和工程文档 | 全链路真实MariaDB/Redis验证，汇总已执行结果 | NODE-C03.6 |

幂等结果与业务写入使用同一工作单元和数据库唯一约束；同键并发竞争失败返回409，事务全部回滚，调用方使用原键重试可读取已提交结果。不得把数据库事务内的执行回调用于外部不可回滚副作用。结果只保存业务数据，不保存每次请求的traceId。业务履约规则按用户确认落实，不在技术幂等节点中推断。

### NODE-C03.2 主数据子节点

用户于2026-10-24确认：物料使用编码、名称、基本单位；客户、供应商、仓库使用编码、名称。同类编码唯一；创建后编码及物料基本单位固定，仅名称可修改；已有业务引用的数据禁止删除。采购、销售订单支持分批收货/出库，累计履约数量不超过订单数量；首期不引入审批流、退货或反向冲销。

主数据使用带固定类型的MasterData聚合，统一维护四类已确认的共同生命周期；物料基本单位由聚合按类型校验，类别不能变更。数据库唯一约束维护同类编码唯一，引用外键阻止删除，版本字段防止覆盖并发更新。绑定SPEC-002、SPEC-005、SPEC-007，查询与HTTP适配仍由C03.5/C03.6交付。

| 子节点 | 目标与输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-C03.2a | MasterData聚合、仓储端口、领域变更事件及领域测试 | UUID、固定编码/单位/类别、名称修改、非法状态及版本校验 | NODE-C03.1b |
| NODE-C03.2b | Easy-Query主数据映射/仓储、Flyway表及真实数据库测试 | 四类CRUD、同类编码唯一、跨类隔离、过期版本冲突、引用阻止删除和回滚 | NODE-C03.2a |
| NODE-C03.2c | 创建/重命名/删除命令与结果DTO、命令处理器和应用编排 | 编译及命令路由、幂等重放、业务/事件/缓存补偿原子性测试；超8文件先继续细分 | NODE-C03.2b |
| NODE-C03.2d | 主数据写能力集成验收与运行文档 | 完整应用真实数据库及Redis验证、事务回滚与重复请求 | NODE-C03.2c |

每个子节点最多8文件，包含状态交接文档；C03.2完成只代表主数据写能力，不能宣称查询和HTTP接口已交付。

C03.2c进一步按行为拆分，以下子节点沿用其规范与范围，每次只实施一个：

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-C03.2c1 | 创建命令、结果DTO、创建处理器、应用编排及集成测试 | 四类创建路由、幂等重放、编码冲突、业务/Outbox/缓存同提交及回滚 | NODE-C03.2b |
| NODE-C03.2c2 | 改名命令与处理器，扩展应用编排及测试 | 版本校验、固定属性、重复重放、更新缓存及Outbox | NODE-C03.2c1 |
| NODE-C03.2c3 | 删除命令与处理器，扩展应用编排及测试 | 引用拒删、过期版本、幂等删除及缓存版本屏障 | NODE-C03.2c2 |

创建后的改名与删除也使用幂等回执保护重试。命令显式携带由服务器构造的主体标识；Phase D前本地接口使用固定本地主体，不能把该字段当作已实现身份认证。缓存键与TTL策略由应用主数据能力统一提供，供后续读取侧复用。


### NODE-C03.3 库存子节点

绑定SPEC-002、SPEC-005、SPEC-007，前置C03.2完成。库存按物料与仓库唯一维护余额；数量使用BigDecimal与DECIMAL(38,12)对齐，最多26位整数和12位小数，超出存储边界拒绝，不静默舍入。库存增加/减少、流水和Outbox在同一工作单元内完成，不提供绕开采购销售规则的公共库存调整接口。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-C03.3a | Quantity值对象、StockBalance聚合/仓储端口、StockChanged事件及领域测试 | 精确小数、边界、禁止负库存、仓库维度及版本 | NODE-C03.2d |
| NODE-C03.3b | StockMovement不可变流水与仓储端口、领域测试 | 正数量、来源单据/履约/行标识、方向和余额快照 | NODE-C03.3a |
| NODE-C03.3c | 库存余额ORM映射/仓储、迁移及数据库测试 | 物料仓库唯一、主数据类别外键、并发版本与回滚 | NODE-C03.3b |
| NODE-C03.3d | 库存流水ORM映射/仓储、迁移及数据库测试 | 同履约行不重复写入、余额引用与事务回滚 | NODE-C03.3c |
| NODE-C03.3e | 库存过账内部应用入口、输入DTO及集成测试 | 入库/出库、库存不足、余额/流水/Outbox原子性、重复及并发保护 | NODE-C03.3d |
| NODE-C03.3f | 库存能力组合验收与文档 | 全链路真实数据库行为及库存/主数据回归 | NODE-C03.3e |

每子节点包含状态交接且不超过8文件；超过时先继续细分。流水保留订单ID、履约ID与订单行ID；库存阶段仅建立来源标识，订单表创建时再补充来源外键。履约规则由C03.4订单领域验证，C03.3不宣称已交付采购销售HTTP闭环。

### NODE-C03.4 采购销售子节点

用户于2026-10-24确认：自动单号、供应商/客户、固定仓库、多条物料明细、数量和人民币单价；金额按数量乘单价汇总后四舍五入到分。创建后明细固定，分批履约推进待履约、部分履约及已完成；首期不包含修改、取消、折扣、审批、退货或反向冲销。单据统一使用TradeOrder聚合区分PURCHASE/SALE，采购引用供应商，销售引用客户；履约数量不得超过订单剩余数量。

绑定SPEC-002、SPEC-005、SPEC-007，前置C03.3；数量复用Quantity，价格和金额使用BigDecimal，不引入浮点计算或财务记账。明细集合与一次履约均限制100行，避免无界请求和数据库写入；人民币单价最多13位整数、6位小数，订单金额最多36位整数、2位小数，超界拒绝。自动单号使用带类别前缀的领域UUID，不承诺连续号或含业务含义的编码。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-C03.4a | 人民币单价值对象、固定订单行与履约数量约束、领域测试 | 精确乘法、数量上限、非法价格及不可变明细 | NODE-C03.3f |
| NODE-C03.4b | 采购销售订单聚合、仓储端口、订单事件与领域测试 | 类别/主体/仓库、单号、金额舍入、状态推进、批量履约原子校验 | NODE-C03.4a |
| NODE-C03.4c | 不可变履约单、明细和仓储端口、领域测试 | 来源、正数量、行数/重复行及固定快照 | NODE-C03.4b |
| NODE-C03.4d | 订单与订单行ORM、仓储、迁移及数据库测试 | 主数据引用、明细固定、乐观锁、精确金额与回滚 | NODE-C03.4c |
| NODE-C03.4e | 履约单与明细ORM、追加仓储、迁移及数据库测试 | 订单行归属、来源唯一、数量及整体回滚 | NODE-C03.4d |
| NODE-C03.4f | 创建订单命令/结果/处理器、应用编排和集成测试 | 采购销售创建、主体类别、幂等/Outbox同提交 | NODE-C03.4e |
| NODE-C03.4g | 履约命令/结果/处理器、应用编排和集成测试 | 分批收发、剩余数量、库存不足、重复重放及并发；超过8文件先细分 | NODE-C03.4f |
| NODE-C03.4h | 库存流水来源外键迁移、更新测试来源夹具及关联测试 | 拒绝跨单据来源、真实订单履约引用、库存回归 | NODE-C03.4g |
| NODE-C03.4i | 采购销售组合验收与文档 | 完整业务/库存/流水/Outbox/回执原子性及并发回归 | NODE-C03.4h |

每节点含CONTEXT和SESSION-HANDOFF且最多8文件；订单业务接口及QueryGateway读取仍待C03.5/C03.6。

### NODE-C03.5 ERP只读查询子节点

绑定SPEC-002、SPEC-005、SPEC-006，前置C03.4完成。所有读取通过QueryGateway和QuerySpec，列表默认20、最大200条；详情复用白名单ID条件或专门的单条查询，未找到由HTTP层转404。Query层使用既有Easy-Query读取独立投影，不依赖Infrastructure实现或写聚合仓储，不启动写事务。只引入根工程已有的ORM依赖与处理器版本，不更换技术路线。

查询字段按每个请求的服务器枚举白名单映射到ORM列，精确、区间和字面包含参数均绑定；同值排序补ID保证稳定分页。订单列表不加载明细；详情最多100行。当前主数据/库存为全局账套，不推断部门归属；Phase D前仅供本地验证，数据权限在D03按可信QueryContext接入，不将上下文透传称为已完成鉴权。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-C03.5a | Query模块ORM依赖、主数据投影/请求/处理器及真实库测试 | 四类隔离、分页/排序、精确/字面包含与注入参数、ID详情 | NODE-C03.4i |
| NODE-C03.5b | 库存余额投影/请求/处理器及真实库测试 | 物料/仓库过滤、数量区间、稳定分页和精确小数 | NODE-C03.5a |
| NODE-C03.5c | 库存流水投影/请求/处理器及真实库测试 | 来源、方向、时间区间、数量和顺序分页 | NODE-C03.5b |
| NODE-C03.5d | 订单摘要投影/请求/处理器及真实库测试 | 类别、交易方/仓库、状态/金额/时间区间，列表不加载明细 | NODE-C03.5c |
| NODE-C03.5e | 订单行投影、订单详情请求/处理器及集成测试 | 明细有界、状态/金额/已履约量、缺失/跨类别详情 | NODE-C03.5d |
| NODE-C03.5f | 履约历史投影/请求/处理器及集成测试 | 指定订单的分批凭据与有界明细、来源一致 | NODE-C03.5e |
| NODE-C03.5g1 | 查询标量资源与存储精度边界 | BigDecimal展开位数有界；时间适配DATETIME(6)范围和微秒精度，非法上下界均提前拒绝 | NODE-C03.5f |
| NODE-C03.5g2 | 读取组合验收及文档，完成C03.5 | 写后读取、回滚不可见、QueryGateway事务拒绝与查询回归 | NODE-C03.5g1 |

查询进入HTTP前补齐数值展开和时间参数边界，避免短指数输入放大JDBC分配或超出当前存储精度；这是查询输入的技术边界，不改变业务数量/价格规则。每节点含测试与两份状态文档，最多8文件；必要时先登记更细子节点。主数据详情缓存复用在具备明确类别及授权边界时接入，不以缓存替代查询权限。

### NODE-C03.6 ERP HTTP协议适配子节点

绑定SPEC-002、SPEC-005、SPEC-006、SPEC-007，前置C03.5完成。写端点在Interface调用CommandGateway，读端点在Query调用QueryGateway，不增加Interface到Query的依赖。版本仍用X-API-Version；写操作要求Idempotency-Key；主体由服务器固定为local-erp，Phase D替换为可信认证主体，客户端不能指定。读上下文由服务器构造。该临时身份仅供本地验证，不代表已交付授权。

资源路径统一以/api/erp开头：/masters/{kind}及其/{id}用于四类主数据；/stock-balances、/stock-movements只读；/orders/{kind}及其/{id}用于采购销售；订单下/fulfillments用于提交和查询分批履约。kind采用已有枚举名称。主数据POST创建、PATCH改名、DELETE删除；订单POST创建，履约POST追加。创建响应201并提供Location，删除204；详情缺失或类别不符404，输入400，版本/库存/幂等冲突409。已确认业务规则保持不变。

GET使用显式白名单条件，排序保留重复sort=field,asc参数，默认page=1/pageSize=20；数字、UUID、时间和枚举在协议边界转换并校验，不能把数据库或内部实现异常笼统改成400。DTO限制既有数量/单价精度、条数和文本长度，构造命令前完成输入校验。OpenAPI注解与示例随端点交付，实际响应继续由统一响应基架包装。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-C03.6a | 主数据写请求、控制器及HTTP测试 | 创建/改名/删除、幂等头、版本及输入错误、固定服务端主体 | NODE-C03.5g2 |
| NODE-C03.6b | Query模块Web依赖、参数适配、主数据列表/详情端点及测试 | 四类隔离、字面包含、重复排序、分页、404及非法输入 | NODE-C03.6a |
| NODE-C03.6c | 库存余额与流水端点、请求适配及测试 | 维度/来源/数量/时间过滤、精度边界及统一响应 | NODE-C03.6b |
| NODE-C03.6d | 采购销售创建请求、控制器及测试 | 固定类别/主体、多行数量/单价、201/Location、幂等及错误 | NODE-C03.6c |
| NODE-C03.6e | 分批履约请求、端点及测试 | 版本、重复/未知行、库存不足、历史幂等重放及错误 | NODE-C03.6d |
| NODE-C03.6f | 订单列表/详情与履约历史端点及测试 | 类别、状态/金额/时间、固定行及分页、缺失资源 | NODE-C03.6e |
| NODE-C03.6g1 | 收货余额存储上限的冲突语义与原子回滚 | 正数量输入合法但累加超DECIMAL(38,12)时返回409，订单/凭据/余额/事件/回执不变 | NODE-C03.6f |
| NODE-C03.6g2 | 真实HTTP组合验收、OpenAPI核对及文档，完成C03.6 | 主数据到采购销售库存闭环、失败回滚、trace/版本/文档 | NODE-C03.6g1 |

每子节点最多8文件并包含两份状态文档；超过限制必须先拆分登记。测试使用本轮MariaDB/Redis，不做部署。

### NODE-C03.7 ERP整体验收

绑定SPEC-002、SPEC-005、SPEC-006、SPEC-007，前置C03.6g2完成。执行全工程单元与独立MariaDB/Redis集成回归，核对模块依赖、主数据缓存/Outbox协作、HTTP错误与重放以及本地服务清理约束；同步README、development、CONTEXT和交接。以实际执行报告区分重新运行、UP-TO-DATE及无测试，不把文档维护本身作为验收。未完成的认证、消息投递、后台任务、网关及观测仍按后续计划推进；本节点不引入部署。若修复超8文件先登记更细节点。

### NODE-D01 认证基础子节点

绑定SPEC-002、SPEC-004、SPEC-005，前置C03.7。每节点包含CONTEXT和交接且最多8文件，超限先细分；按子节点顺序验收、审查、提交并推送。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D01.1 | 无框架Token契约、RS256签发/校验适配器及测试；依赖沿用Boot BOM管理 | Access/Refresh用途隔离、必要claims、时效、错误签名与算法、输入边界 | NODE-C03.7 |
| NODE-D01.2 | 外部RSA密钥与配置装配 | 无默认私钥、配置缺失/错误拒绝、真实签发校验 | NODE-D01.1 |
| NODE-D01.3a | Redis会话端口、创建及Access权威状态校验 | 真实Redis原子建会话、唯一性、用户版本、过期与失败关闭 | NODE-D01.2 |
| NODE-D01.3b | Refresh原子旋转与会话回归 | 并发仅一次成功、旧令牌立即无效、会话绑定、TTL及失败不改状态 | NODE-D01.3a |
| NODE-D01.4 | 已确认账号来源对应的身份验证基础 | 正确/错误凭据、禁止明文存储及边界；实现前按8文件限制细分 | NODE-D01.3b |
| NODE-D01.5 | 登录/刷新与Spring Security接入、可信HTTP主体 | 实际认证HTTP链路、401、幂等主体隔离；实现前细分 | NODE-D01.4 |
| NODE-D01.6 | 认证基础组合验收和文档 | 真实MariaDB/Redis及全部ERP认证回归 | NODE-D01.5 |

D01.3与D01.4按独立依赖优化顺序：先完成安全设计已规定的Redis会话与旋转，再接账号来源。用户已确认本地账号和环境变量初始化、主数据全局共享与订单/库存仓库授权（ADR-003）；不能以JWT基础完成代替登录/授权完成。撤销、RBAC和数据权限分别在D02、D03验收；外部配置仅用于本地运行，不执行部署。

### NODE-D01.4 本地账号子节点

绑定SPEC-002、SPEC-004、SPEC-006、SPEC-007，前置D01.3b；每节点含两份状态文档且最多8文件，必要时继续细分。账号标识由领域生成UUID，用户名是有界登录标识；密码原文不进入领域实体、数据库、事件或日志，凭据查询只供内部认证使用，不提供HTTP密码哈希读取端点。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D01.4a | 本地账号聚合/仓储端口、密码散列端口和领域测试 | 固定身份、仅存散列、重建校验及敏感值不输出 | NODE-D01.3b |
| NODE-D01.4b | BCrypt适配器及测试 | 随机盐、正确/错误密码、UTF-8的72字节上限且不截断 | NODE-D01.4a |
| NODE-D01.4c | 账号ORM/仓储、迁移及真实库测试 | 唯一用户名/初始管理员、事务外拒写、回滚及散列持久化 | NODE-D01.4b |
| NODE-D01.4d1 | 内部凭据端口/快照、只读投影/查询/处理器及测试 | 精确用户名绑定、QueryGateway执行、敏感值不输出 | NODE-D01.4c |
| NODE-D01.4d2 | 凭据端口的QueryGateway适配器及组合测试 | 应用仅依赖端口、无事务读取与无HTTP暴露 | NODE-D01.4d1 |
| NODE-D01.4e1 | 初始管理员工作单元编排与测试 | 首次创建、重复/并发启动不覆盖已有账号或密码 | NODE-D01.4d2 |
| NODE-D01.4e2 | 环境变量启动初始化配置与测试 | 首次缺失拒绝、无默认凭据、重启不重置、异常脱敏 | NODE-D01.4e1 |
| NODE-D01.4f | 本地身份验证应用入口和组合验收 | 正确/错误/未知账号统一认证语义，凭据查询经QueryGateway | NODE-D01.4e2 |

D01.5接入登录/刷新及安全过滤链，D02引入撤销管理与RBAC；初始管理员的权限由RBAC表达，不用用户名硬编码绕过授权。

### NODE-D01.5 HTTP认证子节点

绑定SPEC-004、SPEC-005、SPEC-006、SPEC-007、SPEC-011；前置D01.4，仍逐节点最多8文件，必要时在实现前继续细分。先验收应用会话编排，再接HTTP协议与安全链，最后替换ERP本地主体并完成真实HTTP回归；中间能力不标记为父节点完成。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D01.5a | 登录/刷新/Access验证应用编排及组合测试 | 真RSA、数据库身份及Redis会话，旋转与失败不返回令牌 | NODE-D01.4f |
| NODE-D01.5b | 登录/刷新HTTP协议和脱敏请求响应 | 标准响应、traceId、输入边界、敏感值不进日志 | NODE-D01.5a |
| NODE-D01.5c | Spring Security无状态过滤链与可信主体契约 | Bearer用途、401/403标准错误、Redis失败关闭、无HTTP Session | NODE-D01.5b |
| NODE-D01.5d | ERP写命令和查询接入可信服务器主体 | 不接收客户端自报用户、幂等主体隔离；按文件数继续细分 | NODE-D01.5c |
| NODE-D01.5e | 真实HTTP认证及现有ERP测试夹具迁移 | 真实登录/刷新、匿名与无效令牌拒绝、已认证ERP闭环 | NODE-D01.5d |
| NODE-D01.5f | OpenAPI安全契约与认证回归 | 401/403、安全声明及文档路径与真实行为一致 | NODE-D01.5e |

### NODE-D01.5c 安全过滤链细分

绑定SPEC-004、SPEC-005、SPEC-011，按8文件限制拆分；HTTP主体只能来自验证后的Access声明，不采用请求头自报用户。缺少JWT配置时业务入口必须失败关闭。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D01.5c1 | Spring Security依赖、可信主体与当前身份端口适配 | 只接受服务端认证的主体、线程隔离、未认证拒绝 | NODE-D01.5b |
| NODE-D01.5c2 | Bearer认证过滤器与标准安全错误输出 | 真实签名/权威会话组合、错误用途/损坏/重复头拒绝、上下文清理 | NODE-D01.5c1 |
| NODE-D01.5c3 | 无状态过滤链装配与入口策略 | 登录/刷新与健康端点的明确放行、业务认证、401/403、禁用默认登录与Session、配置缺失失败关闭 | NODE-D01.5c2 |

### NODE-D01.5d ERP主体接入细分

绑定SPEC-004、SPEC-005、SPEC-006、SPEC-007；仅接受CurrentIdentity服务器快照，RBAC和仓库授权仍在D02/D03实现。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D01.5d1 | 主数据/采购销售写控制器接入真实账号、可复用认证HTTP夹具 | 实际HTTP登录、客户端自报身份无效、两个账号幂等作用域隔离 | NODE-D01.5c3 |
| NODE-D01.5d2 | 主数据/库存/订单查询上下文传递可信账号 | 统一HTTP上下文不再匿名，拒绝缺少可信身份，内部登录查询仍匿名 | NODE-D01.5d1 |

## 5. AI 执行提示词模板

执行任一节点时，输入最少包含：

- 当前 nodeId
- objective
- inputSpecs
- prerequisites 状态
- 目标输出文件
- validation 方法

若缺失其中任一项，先补计划，再执行实现。

### NODE-D01.5e 真实HTTP回归细分

绑定SPEC-004、SPEC-005、SPEC-006；按8文件限制拆分，全部完成才验收父节点。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D01.5e1 | 主数据、订单、ERP闭环认证夹具迁移及网络刷新验证 | 23项真实HTTP/数据库/Redis测试 | NODE-D01.5d2 |
| NODE-D01.5e2 | 库存查询HTTP夹具迁移与完整ERP认证回归 | 库存查询及前一节点全部网络测试重新通过 | NODE-D01.5e1 |

D01.5f以前置D01.5e2及父节点D01.5e验收完成为准。

### NODE-D02 撤销与权限实现分解

绑定SPEC-004、SPEC-003、SPEC-005，前置D01完成。已有Refresh白名单和版本检查沿用D01实现，不重复搭建；每节点含状态文档不超过8文件。先完成已由安全规范确定的本人注销能力，RBAC管理产品范围已确认，按下文细分，不推断新业务规则。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.1a | 无框架撤销端口、Redis原子单会话撤销与用户版本递增 | 本人会话绑定、其他会话不受影响、全量失效、刷新竞争、大整数和存储故障 | NODE-D01.6 |
| NODE-D02.1b | 可信主体注销应用编排及logout/logout-all端点 | 真实HTTP注销后旧Access/Refresh拒绝，自报身份无效 | NODE-D02.1a |
| NODE-D02.1c | 撤销组合验收与接口文档核对 | HTTP/Redis注销和既有认证回归 | NODE-D02.1b |
| NODE-D02.2 | 角色/权限持久化与管理 | API/FEAT类型、授权变更与账号管理；确认范围后按8文件细分 | NODE-D02.1c |
| NODE-D02.3 | 方法鉴权、auth/me及ERP API权限绑定 | 已登录无权限403、授权有效、功能权限下发与不可伪造 | NODE-D02.2 |
| NODE-D02.4 | 全工程RBAC与撤销验收 | 真实库/Redis/HTTP回归及文档更新 | NODE-D02.3 |

D02.1不新增账号启停/改密规则，不提供他人强制下线入口；单会话注销删除权威会话，Refresh残留白名单因会话失效不可使用并按原TTL回收。全部退出以权威用户版本原子递增使所有旧Access/Refresh失效，不扫描会话键。后续授权和账号状态变更需复用撤销能力并保障失败关闭。

### NODE-D02.2 权限基础与管理边界

SPEC-004已确定user/role/permission及两类关系、API/FEAT类型。以下基础节点不决定管理员策略或管理产品规则，可在范围讨论期间实施；管理节点按已接受ADR-004细分。读模型继续QueryGateway，运行时授权不信任请求体权限。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2a | 类型化权限值对象、不可变账号权限快照与只读端口 | API/FEAT精确匹配、无通配符、输入有界和不可变 | NODE-D02.1c |
| NODE-D02.2b | 角色/权限/账号角色/角色权限关系迁移与映射基础 | 真实库唯一性、外键和类型边界；按8文件必要时细分 | NODE-D02.2a |
| NODE-D02.2c | 账号有效权限只读查询与端口适配 | 经QueryGateway、无事务、账号状态、关系隔离及有界读取；实现前细分 | NODE-D02.2b |
| NODE-D02.2d | 已确认账号/角色管理及管理员初始化授权 | 按ADR-004和下文细分节点执行 | NODE-D02.2c |

基础节点不提供管理HTTP接口、不授予默认业务权限、不宣布RBAC完成；D02.3在管理与授权源验收后再接入方法鉴权。

### NODE-D02.2b 关系存储细分

按8文件限制先验证迁移约束，再提供持久化映射，不引入管理员或角色管理产品策略。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2b1 | role、permission、user_role、role_permission迁移和真实库约束测试 | UUID、角色编码、权限类型与编码、关系唯一性及外键限制 | NODE-D02.2a |
| NODE-D02.2b2 | 关系ORM映射与读写夹具验证 | Easy-Query映射、精确编码/类型与关系关联，写入仅测试工作单元 | NODE-D02.2b1 |

权限以(type, code)标识，沿用D02.2a严格区分API与FEAT；角色/权限由领域UUID标识，不使用自增ID。迁移不插入默认账号或授权数据。

### NODE-D02.2c 权限读取细分

绑定SPEC-004、SPEC-006；账号状态和权限必须从同一SQL语句读取，去重后最多1024项，超界或存储失败拒绝返回授权结果。不读取或输出凭据，不在应用事务中查询。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2c1 | 三类关系/权限只读投影、固定账号QuerySpec、关联查询处理器及真实库测试 | 多角色去重、API/FEAT隔离、无授权/未知/禁用账号、有界结果、无事务与可信主体 | NODE-D02.2b2 |
| NODE-D02.2c2 | AccountPermissions的QueryGateway适配与权限基础组合验收 | 端口装配、快照隔离、读取错误与边界回归；同步实现文档 | NODE-D02.2c1 |

D02.2c1复用账号只读投影，但选择字段仅为ID和启用状态，不加载密码散列；所有安全查询关闭SQL参数日志。权限仍未用于HTTP放行，管理/管理员授权按已确认ADR-004继续实现。

### NODE-D02.2d 账号与角色管理细分

绑定SPEC-004、SPEC-005、SPEC-006、SPEC-007，前置D02.2c完成；规则见[ADR-004](adr/ADR-004-账号与角色管理.md)。每个节点至多8文件，较大节点在实施前继续细分。文档决策整理不计开发节点完成。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2d1 | 角色领域模型与权限目录 | 固定编码、名称边界、保留管理员角色及精确权限登记 | NODE-D02.2c2 |
| NODE-D02.2d2 | 管理写端口、关系变更与管理员保护存储 | 事务外拒写、唯一性、最后管理员保护及并发；实施前细分 | NODE-D02.2d1 |
| NODE-D02.2d3 | 目录与管理员角色初始化 | 首次授权、重启幂等、权限补登记、不能恢复已移除成员关系 | NODE-D02.2d2 |
| NODE-D02.2d4 | 账号启停与权威版本撤销 | 禁用后Access/Refresh拒绝、重启用旧令牌仍失效、失败关闭；实施前细分 | NODE-D02.2d3 |
| NODE-D02.2d5 | 管理应用用例与可信操作者授权 | 创建普通账号无权限、角色配置/成员分配、最后管理员保护和并发回归 | NODE-D02.2d4 |
| NODE-D02.2d6 | 账号/角色/权限目录只读查询 | 分页白名单、无凭据输出、可信管理员检查；实施前细分 | NODE-D02.2d5 |
| NODE-D02.2d7 | 管理HTTP接口和协议验证 | 401/403、输入边界、禁用/授权立即生效、不能自报身份；实施前细分 | NODE-D02.2d6 |
| NODE-D02.2d8 | 管理能力组合验收 | 真实库/Redis/HTTP及现有ERP回归，文档对齐 | NODE-D02.2d7 |

管理员角色承载目录中的全部权限；后续ERP权限绑定复用该目录。数据权限仍由D03交付；本阶段不新增仓库默认范围。管理并发和数据库/Redis跨存储失败必须通过行为测试，不将单模块编译视作验收。

### NODE-D02.2d2 管理存储细分

绑定SPEC-004、SPEC-007，每节点含状态文档不超过8文件。数据库协调行仅用于串行化低频管理变更，业务角色/账号UUID继续由领域生成。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2d2a | 管理存储端口、最后管理员领域约束、协调行迁移 | 领域保护规则、真实库迁移与并发锁基础 | NODE-D02.2d1 |
| NODE-D02.2d2b | 角色及权限关系写仓储 | 事务边界、固定编码、乐观版本、精确权限、回滚 | NODE-D02.2d2a |
| NODE-D02.2d2c | 账号角色关系与管理员协调存储 | 成员替换、可信管理员判断、并发保护及初始化标记 | NODE-D02.2d2b |

管理用例先锁协调行，再验证当前管理员和目标状态，在同一Application事务内修改。保护约束由Domain定义，Application协调调用，不在Query层写业务规则。禁用账号和角色成员变更共享协调点；持锁后的管理员判断必须读取最新已提交状态，不能依赖较早事务快照。

### NODE-D02.2d3 管理员初始化细分

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2d3a | 同事务目录、管理员角色与首次账号绑定应用编排 | 首次全目录授权、标记同提交、并发初始化、重启不恢复已移除成员 | NODE-D02.2d2c |
| NODE-D02.2d3b | 环境账号初始化后接入角色初始化，调整必要测试夹具 | 实际启动顺序、缺失/失败拒绝启动、凭据不输出；超8文件先细分 | NODE-D02.2d3a |
| NODE-D02.2d3c | 初始化及既有认证组合验收 | 真实MariaDB/Redis/HTTP回归及文档核对 | NODE-D02.2d3b |

绑定SPEC-004、SPEC-007。首次账号角色绑定与initialized标记必须同事务；已完成标记不得在重启时重新授予已被移除的成员关系。目录升级可以补登记权限并同步管理员角色，但不能凭bootstrap标记绕过成员关系。

### NODE-D02.2d4 账号启停与撤销细分

绑定SPEC-004、SPEC-003、SPEC-007，沿用已确认管理范围。先完成写状态和撤销端口，再接入认证与管理应用，最后组合验收。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2d4a | 账号启用状态领域变更与乐观持久化 | 固定用户名/凭据/初始化槽、版本冲突、当前读与回滚 | NODE-D02.2d3c |
| NODE-D02.2d4b | 管理目标账号的权威版本递增端口与Redis适配 | 无会话亦可撤销、精确大整数/溢出、并发、故障脱敏 | NODE-D02.2d4a |
| NODE-D02.2d4c | Access/Refresh逐次验证账号当前启用状态 | 禁用后既有令牌拒绝、未知账号及读取故障失败关闭；复用权限快照端口 | NODE-D02.2d4b |
| NODE-D02.2d4d | 可信管理员账号启停应用编排 | 协调锁与最后管理员保护、Redis失败DB回滚、启停均撤销版本以阻止旧令牌复活 | NODE-D02.2d4c |
| NODE-D02.2d4e | 启停、撤销和认证组合验收 | 登录/刷新竞争、重启用后旧令牌失效、整体认证与ERP回归 | NODE-D02.2d4d |

停用和重新启用均在数据库提交前完成权威版本递增；Redis失败使数据库工作单元回滚。数据库提交失败可能额外使会话下线，但不得返回成功或恢复旧版本。认证读取账号状态使用QueryGateway且不加入写事务；无效JWT仍先拒绝，不能用用户自报ID查询状态。HTTP管理接口留D02.2d7。
