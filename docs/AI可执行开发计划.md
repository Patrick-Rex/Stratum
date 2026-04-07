# AI可执行开发计划

## 1. 目标

本计划将 Stratum 的规范映射为 AI 可长期执行的开发节点图。
原则：节点小、依赖清晰、输出明确、验证可执行。

## 1.1 当前仓库起点

- 当前仓库仍是单模块 Spring Boot 空项目，根目录仅有 [build.gradle](../build.gradle) 与 [settings.gradle](../settings.gradle) 两个最小构建文件。
- Phase A 的首要目标不是继续在根工程叠加业务代码，而是先将根工程收敛为多模块聚合根，再逐步补齐 starter、common 与各分层模块。
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
- prerequisites: NODE-A02, NODE-B01
- actions:
  - 接入 MariaDB、Flyway、Easy-Query
  - 定义 UoW 实现与事务适配
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
- actions:
  - 创建 outbox_event 表与实体
  - 在 UoW 提交内写入 outbox
  - 实现 Outbox 状态流转模型
- outputs:
  - outbox 持久化实现
  - 事务内事件落盘逻辑
- validation:
  - 事务失败时业务数据与 outbox 一起回滚
- handoff: 交给 Phase C

### Phase C: 读模型与 API

#### NODE-C01

- objective: 建立 Query 基架
- inputSpecs: SPEC-006, SPEC-005
- prerequisites: NODE-A03, NODE-B02
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
- prerequisites: NODE-A02, NODE-B04, NODE-C02
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

- objective: 建立网关与负载均衡配置
- inputSpecs: SPEC-010, SPEC-013, SPEC-012
- prerequisites: NODE-A02, NODE-C02
- actions:
  - 增加 Nginx 网关配置
  - 配置 upstream、限流、Header 灰度与 trace 透传
  - 输出网关日志与 exporter 指标
- outputs:
  - gateway 配置文件
  - compose 中的网关服务
- validation:
  - 经网关访问应用成功
  - 关闭一个 app 副本后自动切换
- handoff: 交给 Phase E

### Phase E: 消息与任务

#### NODE-E01

- objective: 建立 RabbitMQ Topic 总线
- inputSpecs: SPEC-008, SPEC-003
- prerequisites: NODE-A02, NODE-B04
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

## 4. 节点批处理规则

- 单次 AI 会话优先执行 1 个节点。
- 若节点较小，可合并同阶段相邻 2 个节点，但不得跨阶段。
- 若单节点涉及超过 8 个文件，应再拆分子节点。

## 5. AI 执行提示词模板

执行任一节点时，输入最少包含：

- 当前 nodeId
- objective
- inputSpecs
- prerequisites 状态
- 目标输出文件
- validation 方法

若缺失其中任一项，先补计划，再执行实现。