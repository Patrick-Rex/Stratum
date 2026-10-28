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
  - 在独立进程 `stratum-gateway` 建立 Spring Cloud Gateway 路由、限流、Header 灰度与 trace 透传配置
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

##### NODE-E04 首期死信重放任务细分

用户于2026-10-28确认首期异步任务用于死信重放：仅任务开始前可取消，只有实际消费成功才算完成。沿用已有RabbitMQ、Outbox、任务工作单元和管理授权基线，不修改消息总线实现；任务登记、发布确认和消费凭据分别表达不同事实。每个叶节点最多8个文件，验证、审查、提交并推送后按顺序继续；必要时进一步拆分。

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E04.1a | 任务未开始取消的存储契约、持久状态和原子竞争；真实数据库验证幂等、取消/领取竞争、已开始不可取消 |
| NODE-E04.1b | 管理员取消应用入口；同一工作单元复核当前资格并执行取消，验证撤权及事务边界 |
| NODE-E04.2a | 稳定绑定重放任务、归档、操作者和请求身份，验证并发幂等及回滚 |
| NODE-E04.2b | 管理员登记重放任务，使用固定处理器和可信身份，验证幂等和权限 |
| NODE-E04.2c | 固定任务处理器复用已有重放登记入口，保留原事件身份；验证失败重试与重复执行 |
| NODE-E04.3 | 只读任务状态及真实消费证据查询，经QueryGateway与当前授权；不能把登记或发送确认为消费成功 |
| NODE-E04.4 | 版本化触发、查询及幂等取消HTTP接口，验证协议、权限与OpenAPI |
| NODE-E04.5 | 真实数据库、Broker、调度及HTTP组合验收和运行说明，完成E04非部署范围 |

###### NODE-E04.2c 固定处理器细分

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E04.2c1 | 固定一次性处理器及严格输入、中断边界；只接入受控执行端口，不自行启动调度 |
| NODE-E04.2c2 | 任务绑定与已有重放登记的实际事务入口；执行时授权按用户确认规则实现 |
| NODE-E04.2c3 | 固定处理器接入调度，验证实际领取、重试与幂等，完成E04.2c |

###### NODE-E04.2c2 执行事务细分

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E04.2c2a | 在已有写工作单元中读取并锁定首次任务绑定，供执行时核对任务、归档和操作者 |
| NODE-E04.2c2b | 依据用户确认的执行时授权规则复核绑定并调用已有重放存储，同事务提交 |

###### NODE-E04.2c2b 执行校验细分

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E04.2c2b1 | 校验已领取任务与持久请求、归档及固定处理器绑定一致，不执行重放 |
| NODE-E04.2c2b2 | 依据已确认授权规则在同一工作单元登记重放，验证撤权、幂等和回滚 |

###### NODE-E04.2c2b2 执行资格与事务细分

用户于2026-10-28确认：管理员提交任务后、开始执行前被停用或撤权时，执行前重新核验，不登记重放。执行资格需持原领取证据并在登记事务内复核；登记和真实消费仍为不同事实。

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E04.2c2b2a | 工作器向处理器传递原领取证据，既有处理器继续兼容原任务输入 |
| NODE-E04.2c2b2b | 在已有工作单元内锁定并核验原领取令牌、完整快照和数据库租期；验证过期、接管与竞争 |
| NODE-E04.2c2b2c | 同一事务复核当前管理员、任务绑定和领取资格，再通过已有重放存储登记；验证撤权、幂等、回滚及中断 |

###### NODE-E04.2c3 调度接入细分

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E04.2c3a | 重放处理器仅接受原领取证据并显式装配，验证输入、中断和开关 |
| NODE-E04.2c3b | 普通Quartz调度与真实重放事务组合验收，验证领取、撤权、重复及退出，完成E04.2c |

##### NODE-E04.3 状态查询细分

任务状态以实际消费证据为成功依据；固定单事件任务的进度仅表示完成数，未完成为0，消费成功为100。登记、技术执行成功和Broker发送均不能替代消费凭据。异常或不一致证据失败关闭，不公开原消息正文、幂等键或领取令牌。

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E04.3a | 基于技术状态、重放状态和可信消费证据的领域状态判定；覆盖等待、失败、取消和不一致证据 |
| NODE-E04.3b | QueryGateway单任务只读查询，统一管理员资格及同一数据库快照的凭据校验；真实数据库验收 |

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

##### NODE-F01 细分顺序

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-F01.1 | 按ADR-008接入并验证Starter的OpenTelemetry Java Agent及W3C HTTP跨度；保留业务关联协议，不启动外部观测服务 |
| NODE-F01.2 | Starter安全结构化日志同时记录业务关联值与真实标准追踪字段；验证无追踪及敏感字段边界 |
| NODE-F01.3 | Starter暴露低基数Prometheus指标并落实已确认的指标访问规则；真实HTTP与指标验收 |
| NODE-F01.4 | 汇总Starter追踪、日志及指标组合验收和本地运行说明；F01全部子节点验收后才标记完成 |

F01仅处理Starter基础能力；Gateway及消息、任务跨边界扩展继续F02。新依赖或Agent版本接入前核对许可、维护状态、兼容性及运行成本；观测后端与部署暂缓。

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
- NODE-D04.1 按ADR-006实现独立网关进程的路由、限流、Header 灰度、trace 与指标并本地验证；NODE-D04.2 的 Compose 服务、项目外负载均衡配置及多副本故障切换部署验收暂缓。
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

D02.1不新增账号启停/改密规则，不提供他人强制下线入口；单会话注销删除权威会话，Refresh残留白名单因会话失效不可使用并按原TTL回收。全部退出以权威用户版本原子递增使所有旧Access/Refresh失效，不扫描会话键。账号启停和成员关系变更复用目标账号撤销能力；角色权限由逐次权威读取保证立即生效，不从JWT缓存授权结论。

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

### NODE-D02.2d5 管理应用细分

绑定SPEC-004、SPEC-007，前置D02.2d4完成。复用既有领域边界、管理协调行及服务器身份，不引入新的管理产品规则；每节点含状态文档不超过8文件。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2d5a | 可信管理员创建普通账号应用及测试 | 普通账号无角色/权限、凭据散列与清零、重复用户名、授权变更及事务失败 | NODE-D02.2d4e |
| NODE-D02.2d5b | 创建角色、修改名称及整组权限配置应用及测试 | 保留管理员角色保护、固定编码、目录白名单、乐观版本与即时权限读取 | NODE-D02.2d5a |
| NODE-D02.2d5c | 替换账号角色应用及测试 | 64项上界、账号版本、最后管理员保护、成员关系与账号版本同事务、撤销失败回滚及并发 | NODE-D02.2d5b |
| NODE-D02.2d5d | 管理应用组合验收与文档 | 创建账号/配置角色/成员分配/启停及既有认证组合回归 | NODE-D02.2d5c |

创建账号的BCrypt计算在写事务外，写入前在协调锁内重新确认当前管理员身份；普通账号不能带初始化槽或默认授权。角色配置沿用固定code、可变name和已登记权限集，保留管理员角色不得被普通配置削减权限。成员关系替换以账号版本保护并递增版本，关系与账号同事务保存，在提交前撤销目标账号旧令牌；角色权限变更通过权威查询即时可见，业务方法鉴权仍由D02.3交付。管理查询与HTTP仍在D02.2d6/d7按顺序实施。

### NODE-D02.2d6 管理只读查询细分

绑定SPEC-004、SPEC-006，前置D02.2d5完成。管理查询只使用服务器身份，不信任客户端提供的管理员标记；全部数据经QueryGateway与QuerySpec读取，不在写事务中查询。每节点含状态文档最多8文件。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2d6a | 当前启用管理员只读查询与管理查询授权组件 | 真实角色关系、禁用/移除即时拒绝、普通账号及伪造权限不能取得管理资格、无凭据与事务边界 | NODE-D02.2d5d |
| NODE-D02.2d6b | 无凭据账号投影及分页/详情查询 | 白名单、稳定分页、状态/版本、未知账号、注入文本与管理员拒绝 | NODE-D02.2d6a |
| NODE-D02.2d6c | 角色分页/详情查询 | 固定白名单、名称/版本、稳定排序、保留角色可读及管理员拒绝 | NODE-D02.2d6b |
| NODE-D02.2d6d | 账号角色及角色权限有界详情 | 64角色/1024权限上界、空关系与未知目标区分、授权变更即时可见 | NODE-D02.2d6c |
| NODE-D02.2d6e | 已登记权限目录查询与管理读侧组合验收 | API/FEAT及名称、权限目录一致、管理查询回归；超8文件先细分 | NODE-D02.2d6d |

只读管理资格依据当前启用账号和administrator成员关系，不能用初始化槽、JWT声明或自报权限代替。普通业务权限的API放行仍由D02.3接入；本组没有HTTP端点。

### NODE-D02.2d7 管理HTTP细分

绑定SPEC-004、SPEC-005、SPEC-006、SPEC-007，前置D02.2d6完成。HTTP仅作协议适配，写入复用已验收Application用例，读取复用QueryGateway。身份只来自已验证Access；输入边界错误映射400时应限于明确的输入校验，不将存储错误冒充输入错误。每节点含状态文档最多8文件，必要时继续先细分。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2d7a | 创建账号、启停及角色分配HTTP | 真实HTTP管理员/普通/匿名、密码只写及输入边界、版本冲突、最后管理员、撤销后认证 | NODE-D02.2d6e |
| NODE-D02.2d7b | 创建角色及配置权限HTTP | 当前管理员、目录白名单、保留角色、版本/非法输入与配置即时读取 | NODE-D02.2d7a |
| NODE-D02.2d7c | 账号列表/详情/角色查询HTTP | 无凭据、分页/排序/未知参数、严格布尔解析、401/403/404及主体不能自报 | NODE-D02.2d7b |
| NODE-D02.2d7d | 角色列表/详情/权限及权限目录HTTP | 查询白名单、类型API/FEAT、边界与空结果、管理资格实时检查 | NODE-D02.2d7c |
| NODE-D02.2d7e | 管理OpenAPI与协议组合验收 | 请求/响应模型、Bearer、错误码、缓存及版本头，回归既有认证和ERP协议 | NODE-D02.2d7d |

路径采用 `/api/auth/accounts`、`/api/auth/roles`、`/api/auth/permissions`，沿用API版本头1，不新增会话来源。管理写入不提供客户端操作者字段；账号用户名与角色编码不开放修改，角色关系与权限整组替换必须提交观察版本。权限目录由后端PermissionCatalog定义，数据库残留的非目录项不成为可授予选项。

### NODE-D02.2d7e 管理文档验收细分

绑定SPEC-004、SPEC-005；前置D02.2d7d完成。管理读取有多个同名嵌套Row类型，先核对生成文档的数据字段与运行时一致，再核对通用协议；每节点仍最多8文件，文档整理本身不计节点完成。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.2d7e1 | 管理OpenAPI数据模型契约及必要的唯一模型标识 | 账号/角色元数据、两类关系、权限目录及写输入；无敏感字段、必填版本、密码只写；如需大范围修正先细分 | NODE-D02.2d7d |
| NODE-D02.2d7e2 | 管理安全、响应与版本协议组合验收 | Bearer与401/403、成功外壳/创建位置、缓存/trace/版本、管理HTTP及原认证/ERP文档回归 | NODE-D02.2d7e1 |

### NODE-D02.3 运行时权限细分

绑定SPEC-004、SPEC-005、SPEC-006，前置D02.2全部验收完成。复用AccountPermissions权威只读端口和CurrentIdentity，不引入新的授权缓存、JWT权限声明或默认业务授权。接口方法在执行业务查询、幂等重放及写事务前调用统一权限检查；请求参数不能指定操作者或授权集合。角色/账号管理继续保留ADR-004确定的当前管理员资格要求，不能仅凭普通API权限绕过。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D02.3a | 可信主体实时权限检查应用入口 | 精确API/FEAT隔离、匿名/停用/未知账号、授权撤回立即生效、存储故障关闭 | NODE-D02.2d8 |
| NODE-D02.3b | GET /auth/me及功能权限下发 | 当前账号ID及有界featurePermissions、无凭据、实时授权、无缓存、401和文档 | NODE-D02.3a |
| NODE-D02.3c | 主数据读写API权限绑定 | 四类分别精确授权、读写隔离、FEAT不放行业务、拒绝写入无副作用与授权撤回 | NODE-D02.3b |
| NODE-D02.3d | 采购销售读写及履约API权限绑定 | 采购/销售与读/创建/履约隔离、幂等重放前授权、拒绝无副作用 | NODE-D02.3c |
| NODE-D02.3e | 库存API权限绑定与运行时组合验收 | 库存余额/流水读取、普通账号默认403、完整ERP授权回归、D02.3完成 | NODE-D02.3d |

各节点仍最多8个文件，必要时实施前继续细分。功能权限仅用于展示，不替代API授权；D03继续实现仓库范围。本阶段授权检查仅表示请求开始执行业务前已读取当前权限，不宣称跨授权变更与在途业务事务提供全局线性化。

### NODE-D03 仓库范围实施细分

绑定SPEC-004、SPEC-006、SPEC-007；前置D02完成。依据ADR-003，主数据全局共享但仍需API权限，订单和库存读写附加仓库范围；ALL/CUSTOM可执行，组织/部门/本人等仅保留类型契约，不推断归属关系。角色配置、多角色并集、管理员固定ALL及普通角色默认无仓库已由用户确认，见[ADR-005](adr/ADR-005-角色仓库范围.md)。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D03.1 | 通用范围类型与不可变仓库范围值对象 | ALL/指定集合、空集合拒绝访问、UUID与容量边界、不可变；不绑定角色/账号 | NODE-D02.4 |
| NODE-D03.2（待细分） | 仓库范围持久化、实时读取及管理入口 | 角色范围独立版本、未知仓库、引用保护、更新立即生效与故障关闭 | NODE-D03.1 |
| NODE-D03.3（待细分） | 订单与库存查询强制范围过滤 | 列表总数/分页/详情/履约历史/库存流水不能越权，客户端过滤不得扩大范围 | NODE-D03.2 |
| NODE-D03.4（待细分） | 创建和履约目标仓库范围检查 | API权限与仓库范围同时满足，幂等重放不绕过，拒绝无写副作用 | NODE-D03.3 |
| NODE-D03.5 | 全工程数据权限验收 | 多仓库隔离、授权变更、管理/认证/RBAC及ERP回归 | NODE-D03.4 |

各节点最多8文件，实施前按依赖继续细分；文档整理不能替代开发验收。仓库范围值对象的空集合表示没有可访问仓库，这一定义不决定账号或角色的默认分配策略。

### NODE-D03.2 角色仓库配置细分

绑定SPEC-004、SPEC-005、SPEC-006、SPEC-007，遵循ADR-005。角色仓库范围使用独立版本，不改写角色名称或API权限版本；每个配置最多1024个指定仓库，超界拒绝，实际合并过滤通过权威角色关系处理，不截断并集。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D03.2a | 角色仓库配置领域模型与写端口 | 普通默认空、管理员固定ALL、类型与版本边界、不可变 | NODE-D03.1 |
| NODE-D03.2b（待细分） | 配置迁移、映射及写仓储 | 既有及新角色默认、独立乐观版本、原子替换、仓库类别外键、回滚和引用保护 | NODE-D03.2a |
| NODE-D03.2c（待细分） | 当前账号目标仓库实时访问查询与角色配置只读查询 | 管理员ALL、跨角色并集、空集、停用/撤权、失败关闭及当前管理员读资格 | NODE-D03.2b |
| NODE-D03.2d（待细分） | 当前管理员范围配置应用与HTTP | 输入、观察版本、管理员保护、无默认扩大、并发与实时生效 | NODE-D03.2c |
| NODE-D03.2e | 管理/授权组合验收 | 真实库/Redis/HTTP回归、文档一致，D03.2完成 | NODE-D03.2d |

### NODE-D03.2b 存储细分

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D03.2b1 | 独立配置/指定仓库迁移、映射与角色首次保存接入 | 既有角色回填、新角色初始值、仓库类别外键、引用删除保护及事务回滚 | NODE-D03.2a |
| NODE-D03.2b2 | 角色仓库配置当前读和乐观写仓储 | 旧版本、原子替换、独立版本、事务外拒绝、管理员不变量、故障及有界读取 | NODE-D03.2b1 |
| NODE-D03.2b3 | 存储组合验收 | 管理/角色初始化/仓储全量回归及文档，D03.2b完成 | NODE-D03.2b2 |

配置与角色一对一，首次保存角色时同事务写入版本0初始配置；已有角色由迁移回填。指定仓库关联必须指向WAREHOUSE类别并保护仍被配置引用的仓库。配置是角色的从属数据，清理角色时其配置及指定关联一起清理；既有成员/权限关系仍沿用原引用约束，当前不新增角色删除HTTP能力。

### NODE-D03.2c 实时查询细分

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D03.2c1 | 目标仓库布尔查询及参数化范围谓词 | 同主体绑定、当前启用账号、ALL/CUSTOM与多角色并集、无凭据、撤权和故障关闭 | NODE-D03.2b3 |
| NODE-D03.2c2 | 无框架目标访问端口、查询适配及当前仓库检查应用 | 服务端身份、无默认放行、401/403、事务外查询及故障传播 | NODE-D03.2c1 |
| NODE-D03.2c3 | 角色范围只读视图、固定详情查询与管理资格保护 | 独立版本/类型/完整集合、空与未知区分、1024边界、当前管理员检查 | NODE-D03.2c2 |
| NODE-D03.2c4 | 范围查询组合验收 | 与账号/角色/权限读取及初始化回归，D03.2c完成 | NODE-D03.2c3 |

目标访问与后续业务过滤均在数据库通过当前角色关联判断ALL或精确仓库关联，不在应用中枚举、截断或缓存多角色的仓库并集；单个配置仍最多1024项。谓词只接受服务端用户UUID及固定列/参数表达式，禁止拼接客户端SQL。布尔目标查询不确认业务资源存在性，不替代API权限和业务目标校验。

### NODE-D03.2d 管理配置细分

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D03.2d1 | 当前管理员整组配置应用用例 | 可信身份、管理协调锁、独立观察版本、管理员固定ALL、无效引用回滚、并发与实时生效 | NODE-D03.2c4 |
| NODE-D03.2d2 | 角色范围GET/PUT管理HTTP与OpenAPI | 401/403/404/409、输入边界、完整集合、缓存/trace/版本及运行时查询即时变化，D03.2d完成 | NODE-D03.2d1 |

API版本1采用 `/api/auth/roles/{id}/warehouse-scope`：GET返回id/version/scopeType/warehouseIds，PUT接受version/scopeType/warehouseIds并返回id/version，version属于独立仓库配置。scopeType仅ALL/CUSTOM，ALL必须空集合，CUSTOM最多1024项且无空UUID；管理员配置不开放改写。缺失角色404、旧版本及仓库引用冲突409、非法范围输入400，存储错误保持500；相同配置且版本匹配时无重复保存。配置实时读取，不需要重新签发JWT，也不撤销全部成员会话。后续角色业务读取过滤仍由D03.3接入。

### NODE-D03.3 业务读取过滤细分

绑定SPEC-004、SPEC-006，前置D03.2完成。范围限制在QueryHandler内与客户端白名单条件按AND组合，并先于计数、分页和明细读取；不是仅在HTTP控制器过滤已取回的数据。固定查询上下文必须有规范UUID主体，匿名401、畸形主体403，自报权限字符串不扩大范围；已有查询测试夹具须显式准备可信身份及ALL范围，不能继续依赖匿名开发上下文。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D03.3a0 | 库存/订单查询验收夹具迁移至可信ALL身份 | 原查询和履约用例使用真实账号、角色及显式ALL范围，清理自身授权记录，不修改生产放行 | NODE-D03.2e |
| NODE-D03.3a1 | 库存余额范围过滤及可信上下文/明确外层别名 | 多仓库总数和分页、目标过滤不能扩大、空/ALL/并集/即时撤回与原余额查询回归 | NODE-D03.3a0 |
| NODE-D03.3a2 | 库存流水按所属余额仓库过滤 | 来源过滤及分页不能越权、变化实时可见、原流水查询及库存HTTP回归 | NODE-D03.3a1 |
| NODE-D03.3b1 | 采购销售摘要范围过滤及原查询夹具迁移 | 两类列表总数/分页/白名单过滤、多仓库隔离与可信ALL夹具回归 | NODE-D03.3a2 |
| NODE-D03.3b2 | 订单详情范围过滤 | 未授权目标不返回头或明细、按现有空结果映射404、跨角色范围与版本快照回归 | NODE-D03.3b1 |
| NODE-D03.3b3 | 履约历史按所属订单仓库过滤 | 历史总数/分页/明细均不越权、客户端postingId/orderId不扩大范围 | NODE-D03.3b2 |
| NODE-D03.3c | 业务读取范围整体回归 | 管理/认证/RBAC、仓库查询与ERP闭环，D03.3完成 | NODE-D03.3b3 |

使用固定参数化范围谓词时，主体必须绑定UUID值，关联业务目标列必须有明确的外层表别名；不允许依赖嵌套SQL中的未限定列名。库存流水关联余额取得仓库，履约关联订单取得仓库，不能采信客户端补充的归属字段。每节点仍最多8文件，必要时继续先细分；不提供匿名或特殊测试账号的生产放行路径。

### NODE-D03.4 写入仓库检查细分

绑定SPEC-004、SPEC-006、SPEC-007，前置D03.3完成。HTTP分别检查对应创建/履约API权限和目标仓库，全部发生在CommandGateway、写工作单元及幂等回执读取之前；范围撤回后原键也不能重放。内部库存过账仍只由订单履约用例调用，不增加独立HTTP写入口。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D03.4a | 订单创建目标仓库范围绑定及真实HTTP验收 | 采购/销售创建权限与仓库范围独立、默认拒绝、指定/ALL/撤回、原键重放及拒绝无写副作用 | NODE-D03.3c |
| NODE-D03.4b1 | 当前账号原订单仓库访问端口、查询适配及应用入口 | 复用已过滤订单摘要QueryGateway，可信主体、固定类别/ID、当前范围、身份/事务/存储故障，不要求读取API替代履约权限 | NODE-D03.4a |
| NODE-D03.4b2 | 采购收货/销售出库绑定原订单仓库授权 | 从数据库原订单判断范围，不采信客户端归属；履约权限独立、分批过账、撤回后原键拒绝、无跨仓库库存/事件/回执副作用 | NODE-D03.4b1 |
| NODE-D03.4c | 写入范围组合验收 | 创建/履约范围与认证、RBAC、幂等、库存一致性及原ERP回归，D03.4完成 | NODE-D03.4b2 |

创建请求有明确目标仓库，复用当前仓库检查入口，范围不允许403。原订单目标采用既有受范围限制的摘要查询；缺失、类别不符或无范围按不可访问目标返回404，不暴露仓库归属，也不从请求体读取warehouseId。原订单访问端口仅供应用用例使用，不新增HTTP读取或绕过数据范围的查询。API权限与数据范围分别检查，只有履约权限并具有仓库范围的账号仍可履约，无需额外授予读取API。各节点仍最多8文件，必要时在实施前继续细分。

### NODE-D04.1 独立网关本地实施细分

绑定SPEC-010、SPEC-012，补充SPEC-002、SPEC-005；前置D03完成，并先按[ADR-006](adr/ADR-006-独立网关进程.md)更新架构/SDD/依赖规范及审查。使用官方Spring Cloud Gateway Server WebFlux/Netty独立运行，业务MVC及数据库仍留在Starter。具体稳定版本在依赖节点核对，不采用预览版或付费依赖；每节点最多8文件。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D04.1a1 | 统一管理兼容的Gateway依赖并解除Starter聚合 | 官方兼容/许可核对、实际依赖解析、Gateway编译及Starter构建；不把零测试算通过 | NODE-D03.5 |
| NODE-D04.1a2 | 独立Gateway启动入口、配置和生命周期 | 独立随机端口启动/健康检查，无业务数据库依赖，测试进程退出 | NODE-D04.1a1 |
| NODE-D04.1b | 有界固定上游路由与Header灰度/回滚配置 | 路径归属、正常转发、Header命中和关闭回退、非法上游配置拒绝；不接受请求指定上游 | NODE-D04.1a2 |
| NODE-D04.1c | 追踪/业务协议透传与安全统一错误 | traceparent/请求ID、Bearer/版本/幂等键、上游超时/连接失败、错误不泄露内部信息 | NODE-D04.1b |
| NODE-D04.1d | 请求头/请求体防护及管理来源网段控制 | 伪造代理来源不能扩大权限，非法头/超限请求拒绝，管理CIDR与精确路径覆盖 | NODE-D04.1c |
| NODE-D04.1e（待细分） | Redis基础限流及故障关闭 | 有界键/过期、跨实例共享限额、429标准响应、存储故障不绕过 | NODE-D04.1d |
| NODE-D04.1f | 网关结构化访问日志及指标 | upstream/status/latencyMs/traceId/clientIp关联，低基数指标与Prometheus读取，无凭据日志 | NODE-D04.1e |
| NODE-D04.1g | Gateway与真实ERP本地双进程组合验收 | 经网关认证/ERP权限与范围/幂等/追踪/限流回归；退出自身进程，D04.1完成 | NODE-D04.1f |

入口治理不代替ERP业务鉴权，Gateway不导入业务模块或访问数据库。Gateway上游为单个固定HTTP(S)地址或外部负载均衡地址，不增加项目内节点选择。部署D04.2、Compose、外部负载均衡健康摘除及故障切换仍暂缓；D04.1完成后继续Phase E。必要时在实现前继续细分，规范维护本身不记为开发完成。

### NODE-D04.1c 追踪及错误协议细分

前置D04.1b完成。现有Servlet入口已有安全关联标识规则，先在Common共享纯策略，再接入响应式协议和上游错误；避免在两个运行时重复定义标识语义。每节点仍最多8文件，不使用ThreadLocal传递WebFlux请求上下文。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D04.1c1a | Common关联标识策略及Servlet入口复用 | 合法标识保持、非法/缺失生成、长度/字符边界及既有Servlet分派/线程恢复回归；SPEC-011/012/005 | NODE-D04.1b |
| NODE-D04.1c1b | WebFlux关联标识、W3C头和业务协议透传 | traceparent/请求ID、Bearer/版本/幂等键、重复及非法关联头处理、并发请求隔离、响应头与上游一致；SPEC-010/012/005 | NODE-D04.1c1a |
| NODE-D04.1c2 | 有界上游超时及安全统一错误 | 连接/响应超时、无额外写重试、统一外壳/trace/状态，业务错误原样透传，不泄露上游或异常；SPEC-010/012/005，D04.1c完成 | NODE-D04.1c1b |

共同的本机HTTP夹具可在协议节点按实际复用提取，原路由验收断言保留。上游业务响应不由网关二次包装；网关自身错误按统一API外壳转换。请求体/管理来源和Redis限流分别由d/e接入，不在本节点提前实现。

### NODE-D04.1c2 超时与统一失败外壳细分

现有ApiResponse失败工厂从ThreadLocal取得traceId，响应式错误不能直接使用。先补充显式traceId入口，复用统一模型后再接入代理超时，避免重复定义响应DTO或临时绑定事件线程；每节点最多8文件。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D04.1c2a | Common显式traceId失败工厂 | 五字段外壳、数据为空、不可变明细、输入边界、无线程状态副作用及原工厂兼容；SPEC-005/011/012 | NODE-D04.1c1b |
| NODE-D04.1c2b | Gateway有界代理超时和安全错误处理器 | HTTP连接/响应超时、统一外壳与关联、未知路由、上游业务错误原样、无额外写重试及故障脱敏；SPEC-010/005/012，D04.1c2与c完成 | NODE-D04.1c2a |

### NODE-D04.1d 请求防护细分

绑定SPEC-010、SPEC-005、SPEC-012，前置D04.1c完成。头与体的有界防护先独立交付，管理来源规则确认后再绑定；每节点含状态文档最多8文件。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D04.1d1 | 有界头/体校验及代理来源头隔离 | 固定长度与分块超限均不触达上游，合法体保持、非法头拒绝、并发/读取超时有界、错误外壳及原协议回归 | NODE-D04.1c2b |
| NODE-D04.1d2 | 已确认的管理路径及直连CIDR控制 | 管理精确路径/子路径、IPv4/IPv6、非法配置、伪造代理头无效、非管理路径及业务鉴权独立，D04.1d完成 | NODE-D04.1d1；管理规则确认 |

请求大小、缓冲并发和读取等待使用可校验启动参数；超过大小返回413，头容量431，缓冲容量耗尽503，读取超时408。先完整有界读取再转发，避免超限请求造成部分写入；已由Netty在协议解析阶段拒绝的非法报文不保证进入应用JSON错误处理。来源头不能改变网关直连地址或作为客户端指定上游的依据；不引入代理信任链或部署配置。

### NODE-D04.1e Redis限流细分

绑定SPEC-010、SPEC-003、SPEC-005、SPEC-012；前置D04.1d完成。用户已确认直连IP共享令牌桶、20/s补充与40突发、只限制ERP转发及Redis故障关闭。每节点含状态最多8文件。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D04.1e1 | 响应式Redis依赖、有界共享令牌桶及真实存储验收 | 原子消费/补充、跨实例共享、客户端槽位上界与过期清理、命令时限、配置一致性及无存储故障放行；不提前绑定转发 | NODE-D04.1d2 |
| NODE-D04.1e2 | 入口限流错误码及统一协议 | 40002/HTTP429、Retry-After保留、五字段/关联/故障脱敏，原Common和网关错误回归 | NODE-D04.1e1 |
| NODE-D04.1e3 | 将共享桶绑定固定ERP路由及真实HTTP验收 | 稳定/灰度及两个网关共享IP额度、仅路由转发受限、429无业务效果、Redis故障503且不触达上游、客户端头不能换桶，D04.1e完成 | NODE-D04.1e2 |

沿用Boot统一管理的Spring Data Redis响应式客户端，不引入付费限流组件。共享存储按受信启动命名空间使用固定数量Redis键，客户端槽位限制及惰性过期清理都在同一个Lua脚本中原子执行；新身份容量耗尽拒绝服务，不通过合并客户端或随机驱逐绕过独立IP配额。时间由Redis提供，所有状态声明TTL，跨网关配置不一致失败关闭。只读健康探测和不存在的路由不消耗额度；真实Redis测试通过独立integrationTest任务显式执行，普通HTTP协议回归可注入测试替身，生产不提供默认关闭保护的旁路。

### NODE-D04.1f 访问观测细分

绑定SPEC-010、SPEC-012，前置D04.1e完成。日志和指标分别交付，每节点含状态最多8文件，不把文档拆分计为开发节点完成。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D04.1f1 | 安全结构化访问日志及JSON格式 | upstream/status/latencyMs/traceId/clientIp、公共服务/环境/时间/级别/跨度字段；成功/拒绝/未知路径关联，无凭据/请求体/查询原值 | NODE-D04.1e3 |
| NODE-D04.1f2 | 低基数入口指标及Prometheus端点 | 请求/拒绝/故障/耗时及JVM指标可抓取，无IP/用户/原路径/trace标签；指标访问规则确认后绑定来源保护，D04.1f完成 | NODE-D04.1f1；指标入口规则确认 |

当前只做本地日志与指标能力，不启动Grafana/Loki/Jaeger或执行部署。日志的traceId继续使用已验收的入口关联标识，尚无OTel跨度时不伪造spanId；Java Agent完整追踪按F01接入。请求取消及已提交流的观测边界应明确说明，不能把尚未实现的全链路采集记为当前能力。

### NODE-D04.1f2 指标交付细分

绑定SPEC-010、SPEC-012。指标实现、来源保护及验收涉及5个代码/构建/配置文件，加上状态文档已达8文件；README与真实Redis指标组合放入后续验收子节点，不能在基础实现后提前宣布父节点完成。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D04.1f2a | 入口计时、入站/出站原生观测有界约定、受保护Prometheus入口 | 真实文本抓取、成功/429/503/取消、JVM/直方图、任意方法/路径不能生成动态标签；普通回归和真实Redis原功能回归 | NODE-D04.1f1 |
| NODE-D04.1f2b | 真实Redis限流与指标组合验收及完整启动指引 | 两真实网关中429的本地计时/Prometheus结果与原子共享桶一致，抓取不消耗额度，README/模块能力与启动配置一致；f2及f完成 | NODE-D04.1f2a |

用户已确认Prometheus入口沿用管理来源CIDR、health保留健康探测用途。观测约定在Meter.Id创建前约束客户端自选方法与动态URI，不只修改导出标签；网关计时统计至响应提交，499仅为取消日志/指标标记。仍不启动外部观测服务或执行部署。

### NODE-D04.1g 双进程ERP验收细分

绑定SPEC-010、SPEC-004、SPEC-005、SPEC-012，前置D04.1f完成。Gateway以实际可执行JAR启动为独立进程，ERP使用真实数据库/Redis和认证的Starter上下文，不把Gateway业务类加入Starter运行时或测试扫描路径。每节点最多8文件。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-D04.1g1 | 有界Gateway子进程夹具及进销存完整HTTP闭环复用 | 真实独立JAR/随机端口与健康、经Gateway主数据/分批采购销售/库存/幂等/版本；只关闭自身进程/键，直连原闭环继续通过 | NODE-D04.1f2b |
| NODE-D04.1g2 | 真实认证/权限/仓库范围与入口拒绝组合验收 | Bearer/版本/关联透传、匿名及Refresh拒绝、API和范围即时撤回、原键不得越权重放、入口限流无ERP业务效果 | NODE-D04.1g1 |
| NODE-D04.1g3 | 非部署Phase D全工程收敛 | 全部单元、Gateway与Starter真实集成重跑；核对模块隔离、文档/生命周期及部署暂缓项，D04.1完成后继续E01 | NODE-D04.1g2 |

子进程必须限制就绪与退出等待、使用测试工具链JDK25、参数以结构化列表传递，敏感连接信息只从测试环境传入。不得停止用户已有应用或共享数据库服务；测试只删除其随机命名空间和业务夹具。若需扩大文件数，在进入相关子节点之前继续细分。

### NODE-E01 消息基础设施细分

绑定SPEC-008、SPEC-003、SPEC-007、SPEC-012，前置D04.1与B04完成。按[ADR-007](adr/ADR-007-有序消息与原生重试.md)使用原生延迟重试，替代父节点物理retry队列要求。每叶节点包含状态文档最多8文件，按表顺序验收、审查、提交、推送。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-E01.1 | AMQP依赖与显式启用的有界连接配置 | Boot统一版本；关闭时无Broker依赖/健康干扰；启用时必需凭据与配置边界校验，不自动开始消费 | NODE-D04.1g3 |
| NODE-E01.2 | Topic/Quorum原生重试/DLQ拓扑 | 现有Broker隔离vhost真实声明、持久化/路由/有界容量/死信与原生延迟语义，不修改用户资源 | NODE-E01.1；消费用途确认 |
| NODE-E01.3 | 供Outbox Relay使用的发布端口与确认适配 | 持久消息、messageId与trace/序号，mandatory不可路由及confirm失败/超时可判定；业务无直接发送入口 | NODE-E01.2 |
| NODE-E01.4 | 消息总线真实组合验收与操作指引 | 真实Topic分发/原生重试/DLQ与连接退出；E01完成，不提前启动Relay | NODE-E01.3 |

E02依次实现持久化序号与迁移、Relay状态/领取与失败重试、数据库消费去重/有序进度、已确认业务消费者、死信重放及跨实例故障验收；进入E02前继续登记可验收叶节点。原生延迟本身不提供同聚合业务顺序保证，必须与数据库进度配合。E03才接入Quartz/ShedLock周期执行；不部署外部服务。尚未确认的消费者业务用途只阻塞依赖它的节点，不阻塞E01.1。

### NODE-E01.3 发布交付细分

绑定SPEC-008、SPEC-003、SPEC-007、SPEC-012，前置E01.2完成。端口、传输资源与确认适配分别验收，确保每叶节点含状态文档最多8文件。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-E01.3a | Outbox专用发布端口、持久化序号信封和可判定结果 | UUID原信封复用、正序号、不可变性与结果语义；Domain无框架依赖，未接入Relay | NODE-E01.2 |
| NODE-E01.3b | 发送传输的有界NIO写队列与独立生命周期 | 有界写入等待/工作资源、握手失败和真实拓扑重试回归、关闭无线程残留 | NODE-E01.3a |
| NODE-E01.3c | 发布确认适配、不可路由/否定/超时隔离 | 持久JSON/messageId/序号/关联头、mandatory返回与confirm必须同时成功；确认超时清理独立发布连接，不以调用成功代替投递成功；限并发且无生产业务直接发送 | NODE-E01.3b |

E01.4再补齐消息基础设施组合验收与完整启动说明后标记E01完成。发布器仅供后续Outbox Relay使用，不在本阶段添加写事务内MQ调用或无锁定时任务。

### NODE-E02 顺序、Relay与消费细分

绑定SPEC-008、SPEC-007、SPEC-003、SPEC-012；前置E01、B04完成。按ADR-007保证同aggregateId前序消费成功后再推进后序，死信阻塞该聚合而不阻塞其他聚合。每叶节点连同状态文档最多8文件，超出时先继续细分；依表顺序验证、审查、提交、推送。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-E02.1a | 同事务持久化聚合序号/进度及Outbox映射 | 连续序号、原事件身份、提交/回滚/JSON失败、无事务拒绝；非空旧事件无法证明顺序时显式失败而不猜测 | NODE-E01.4 |
| NODE-E02.1b | 序号分配真实并发与原业务回归 | 同聚合跨连接串行、不同聚合独立、并发回滚不留序号空洞、原Outbox/ERP写入仍原子，E02.1完成 | NODE-E02.1a |
| NODE-E02.2a | Relay领取与状态结果端口 | 有界批次、持久化租约及竞争令牌、可判定完成/失败语义，无框架泄漏 | NODE-E02.1b |
| NODE-E02.2b | 有序头事件领取与状态持久化 | 只有前序消费后可领取、跨实例唯一有效租约、到期接管、旧令牌不能完成、失败重试/超限不跳过聚合 | NODE-E02.2a |
| NODE-E02.2c | Application有界Relay批次 | 工作单元只用于领取/最终状态，MQ在事务外，沿用eventId重试，confirm后才SENT，不启动周期调度 | NODE-E02.2b |
| NODE-E02.2d | 真实DB/Redis/Broker发布故障组合 | 返回/否定/故障后重试、崩溃租约恢复、同聚合消费前不推进且其他聚合继续，E02.2完成 | NODE-E02.2c |
| NODE-E02.3a | 数据库消费唯一凭据与顺序进度端口/存储 | 事件与消费组唯一、重复无副作用、非法或非头事件拒绝/等待、进度与消费记录同事务 | NODE-E02.2d |
| NODE-E02.3b | 缓存失效补偿与ERP事件留痕用例 | 复用版本失效屏障与TTL契约、审计原值不丢精度、失败回滚不推进、重复不产生新效果 | NODE-E02.3a |
| NODE-E02.3c | 有界手动ack消费者与原生重试 | 成功提交后ack、失败reject计数、等待nack不计失败；消息契约、prefetch/并发/退出边界明确 | NODE-E02.3b |
| NODE-E02.3d | 有序消费与重复投递真实组合验收 | 两实例重复只一次效果、缓存真实补偿、ERP留痕、前序等待/失败阻塞与其他聚合进度，E02.3完成 | NODE-E02.3c |
| NODE-E02.4a | 死信持久化与聚合阻塞证据 | 原消息/序号/trace与错误状态可追溯、重复收存幂等、不跳过失败前序 | NODE-E02.3d |
| NODE-E02.4b | 人工重放Application用例与持久化投递意图 | 重放仍经过Outbox而非直接MQ、恢复原身份与序号、成功后继续后序、无效或重复请求可判定 | NODE-E02.4a |
| NODE-E02.4c | 重试超限与人工恢复真实验收 | 原生DLQ、持久化死信、重放后恢复且无重复副作用，E02.4完成 | NODE-E02.4b |
| NODE-E02.5 | 消息闭环全工程收敛与运行指引 | 全部单元与显式集成、事务/顺序/生命周期/文档核对，E02完成后进入E03 | NODE-E02.4c |

序号由数据库在Application已有工作单元中分配，竞争锁保持到事务结束，不用时间、随机UUID或单进程计数器代替顺序证据。非空旧事件迁移不自动回填未知因果；本轮E01.4结束时独立测试库Outbox实际0行。E02不添加无ShedLock周期任务，Quartz/ShedLock仍在E03；后台任务API按E04交付。消费者启用及停止必须明确配置和资源边界，不能改变默认关闭消息时的ERP行为。

### NODE-E02.3c 消费适配细分

绑定SPEC-008、SPEC-003、SPEC-007、SPEC-012，前置E02.3b完成。先验证传输契约与确认分支，再接入真实生命周期，避免在一个节点混合超出8文件的代码、测试及状态文档。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-E02.3c1 | 严格信封解码与手动ack/nack/reject适配 | 大小/格式/原身份/头体一致性；消费提交结果成功才ack，WAITING只nack，失败reject；错误日志不含正文 | NODE-E02.3b |
| NODE-E02.3c2 | 显式启用、有界并发/prefetch及退出的监听容器 | 关闭不启动线程，配置边界、真实Broker原生重试/确认与连接退出；E02.3c完成，完整跨实例效果继续E02.3d | NODE-E02.3c1 |

消费适配只注入Domain的OutboxConsumer端口，不使Infrastructure依赖Application。独立消费开关默认关闭；开启要求消息基础设施已启用，固定订阅与原拓扑一致。Broker失败计数沿用已确认的reject与nack区别；不添加本地重试循环或无锁周期任务。

### NODE-E02.4 死信与重放交付细分

绑定SPEC-008、SPEC-007、SPEC-003、SPEC-012。保持SENT不等于消费成功及同聚合严格顺序；死信/重放均记录原eventId/sequence，不能重新分配业务事件身份。每叶节点含状态文档最多8文件。

| 子节点 | 输出 | 验证 | 前置 |
|--------|------|------|------|
| NODE-E02.4a1 | 有界原死信值/存储端口、迁移和持久化去重 | 原消息/身份/trace/失败分类留存，非法信封可隔离归档，唯一凭据与回滚，未知内容不推进顺序 | NODE-E02.3d |
| NODE-E02.4a2 | 死信归档Application入口及事务结果端口 | 提交后返回，外层事务拒绝、失败不假成功，非法归档不变业务进度 | NODE-E02.4a1 |
| NODE-E02.4a3 | 两个DLQ有界手动确认监听与真实归档 | 归档提交后ack，失败保留DLQ消息，资源/退出及默认关闭，E02.4a完成 | NODE-E02.4a2 |
| NODE-E02.4b1 | 人工重放契约与持久化Outbox投递意图 | 验证原事件/聚合头/订阅，重复请求幂等，已消费可判定；保留原身份且不直接发送 | NODE-E02.4a3 |
| NODE-E02.4b2 | Relay领取重放投递意图及有界恢复 | 同一租约/令牌与数据库时钟边界，MQ仍事务外，状态不覆盖原事件历史 | NODE-E02.4b1 |
| NODE-E02.4b3 | Application人工重放入口与真实事务验收 | 仅登记持久意图、拒绝无效/重复效果、成功消费关联恢复，E02.4b完成 | NODE-E02.4b2 |
| NODE-E02.4c | 原生超限、死信归档、人工重放与后序恢复组合 | 真实DLQ/数据库/Redis/Broker，原事件一次效果，其他聚合不受阻，E02.4完成 | NODE-E02.4b3 |

SENT原事件不倒退为PENDING；人工重放使用单独持久化Outbox投递意图，Relay仍发送原eventId和sequence。非法或未知死信只供追溯，重放入口必须重新核验本地原事件，不能把不可信归档当作业务命令。HTTP任务接口和自动调度仍由E04/E03交付，不在此引入新权限端点或无锁定时器。超过预计文件数时继续登记细分，不混入下一节点实现。

### NODE-E03 细分：持久化调度与执行治理

主SPEC-009，补充SPEC-003/012；执行记录写入同时遵循SPEC-007。E02已通过762项全工程回归。依次完成并独立验收、审查、提交、推送；每叶节点含状态文档最多8文件，超过时先继续登记拆分。

| 子节点 | 目标与输出 | 行为验收 | 前置 |
|--------|------------|----------|------|
| NODE-E03.1 | 集中Quartz/ShedLock依赖与Flyway持久化表 | 实际MariaDB迁移、表键/约束和依赖边界；不将空Job模块计为测试通过 | NODE-E02.5 |
| NODE-E03.2 | 显式启用的JDBC集群Scheduler与数据库时间ShedLock装配 | 两独立调度器共享存储、重启可见及有界退出，默认关闭 | NODE-E03.1 |
| NODE-E03.3 | 执行记录、幂等键和dead_job的Domain端口及事务适配 | 真实数据库提交/回滚、独立连接竞争和重放资格；按契约/存储/应用继续拆分 | NODE-E03.2 |
| NODE-E03.4 | 有界执行、可中断超时、指数退避与人工dead_job重放 | 实际ShedLock竞争、超时中断、失败归档及恢复；继续拆分执行器/持久化重试/组合测试 | NODE-E03.3 |
| NODE-E03.5 | 受ShedLock保护的Quartz周期Outbox Relay | 两调度实例下不并发执行、真实Outbox消费、暂停开关和关闭 | NODE-E03.4 |
| NODE-E03.6 | 调度阶段全工程回归与运行指引 | 日志字段、生命周期、失败恢复和完整集成收敛，E03完成后进入E04 | NODE-E03.5 |

Quartz与ShedLock沿用已冻结选型，使用现有MariaDB；Quartz内部JobStore及ShedLock协调事务由其技术适配维护，业务及执行记录仍通过Application工作单元写入，Job不直接访问数据库。SQL建表由Flyway管理，不运行Quartz自带含DROP的初始化脚本，不修改已提交迁移。调度默认关闭，显式启用后才创建调度线程与触发器；E03不引入未确认的任务业务规则或HTTP入口。任务定义、重试上限、超时与锁租期均有界，未取得锁不执行；定时任务必须经过ShedLock注解代理。技术执行记录不包含业务正文和异常原文。多实例验收在本机独立调度器进行，部署节点继续暂缓。

#### NODE-E03.3 执行记录细分

依次执行E03.3a → b → c → d → e；每叶节点主SPEC-009、补充SPEC-003/007/012，含状态文档最多8文件。

| 子节点 | 输出 | 验收与边界 |
|--------|------|------------|
| NODE-E03.3a | 框架无关任务定义、不可变执行/领取/结果与存储端口 | Domain值对象边界、退避与安全摘要测试；尚无实际任务执行 |
| NODE-E03.3b | 执行记录、尝试日志、dead_job及人工重放请求迁移 | 实际MariaDB外键/唯一幂等/状态与租约一致性；不改旧迁移 |
| NODE-E03.3c | 幂等登记、到期候选与竞争领取事务适配 | 两独立连接领取、重复登记、到期接管、回滚与旧令牌拒绝 |
| NODE-E03.3d | 完成、失败退避、dead_job及幂等人工恢复存储 | 真实提交/回滚、超限留痕、恢复关联、迟到完成与重放竞争 |
| NODE-E03.3e | Application执行记录工作单元入口 | 事务外编排/调用返回后提交可见、trace恢复和失败回滚，E03.3完成 |

执行记录是调度技术状态，不定义新的ERP规则；参数正文有界且不输出日志，失败使用固定分类。租约与到期使用数据库时间；领取及完成通过Application工作单元，执行本身在事务外。人工恢复保留原执行身份与历史尝试，使用独立请求ID去重和新竞争令牌，不允许旧执行覆盖新状态。实际调度执行、可中断超时及Quartz重试触发仍在E03.4，任务HTTP业务范围仍待E04确认。

#### NODE-E03.4 执行治理细分

依次E03.4a → b → c → d → e；主SPEC-009、补充SPEC-003/007/012，逐节点验收、审查、提交、推送，含状态文件不超过8个，必要时先进一步拆分。

| 子节点 | 输出 | 行为验收与边界 |
|--------|------|----------------|
| NODE-E03.4a | ShedLock数据库时间锁续期与有界生命周期 | 真实30秒锁跨原期限续期、两提供者互斥、释放恢复和线程退出；不新增任务 |
| NODE-E03.4b | Application任务处理器端口、Job固定注册表及所需依赖 | 任务代码/重复/容量/定时定义校验，空注册表不创造业务任务 |
| NODE-E03.4c | 有界执行与受锁保护的工作器、超时/关闭协调 | 真实DB/ShedLock下事务外处理、实际中断超时、同代码不并发、容量不提前释放及退出；不以Future已结束代替处理线程已退出 |
| NODE-E03.4d | Quartz已登记处理器触发及持久化重试驱动 | 每代码固定轮询触发、有限到期领取、Cron登记与幂等计划时刻、重启/双调度器；所有调度Job均经ShedLock注解代理 |
| NODE-E03.4e | 实际执行失败、死亡、人工恢复及多实例组合 | Quartz真实触发/退避/超时与DB历史闭环、trace/日志及关闭验收，E03.4完成 |

复用ShedLock维护的KeepAlive能力和现有MariaDB，续期工作池有界并随上下文退出。实际执行可使用JDK25支持的稳定虚拟线程能力；本实例并发和未退出任务数量必须有限，资源容量仅在实际执行结束后释放。跨实例互斥仍由ShedLock和持久化令牌负责，不能由本地容量控制代替。超时与任务结束/结果提交必须协调，避免迟到中断污染结果写回；处理器必须幂等、支持中断并约束外部I/O。未停止的处理不冒充执行成功，不以强制终止Java线程清理副作用。

Quartz负责固定代码的到期/重试驱动，执行候选与退避继续以数据库为准，不为每个重试创建内存等待队列。登记表只接受Spring已登记处理器，首次技术Cron任务初始参数为{}，业务用途仍按E04确认。E03.5再接入现有Outbox Relay，不在此增加新ERP副作用、HTTP接口或部署。

##### NODE-E03.4c 有界执行进一步细分

实际执行核心、装配、集成与设计同步预计超过8文件，先按顺序拆分，主SPEC-009、补充SPEC-003/007/012。

| 子节点 | 输出 | 验收 |
|--------|------|------|
| NODE-E03.4c1 | 执行结果分类及单次超时/中断/收尾协调核心 | 真实虚拟线程与中断竞争，迟到信号不污染收尾，启动前停止及上下文阶段；不计为处理器或跨实例验收 |
| NODE-E03.4c2 | 有界执行器、ShedLock工作器及显式装配 | 真实Application/数据库/ShedLock的事务外处理、实际超时、同代码互斥、在途容量和关闭、日志与trace，E03.4c完成 |

协调仅保护同进程一次执行的资源和截止信号，不处理分布式资格；跨实例仍由已实现的ShedLock及数据库令牌控制。并发预算在实际处理与代理解锁完成后释放；提前超时的调用返回不等于后台执行已停止。核心与集成各叶含文档不超过8文件，分别审查、提交和推送。

##### NODE-E03.5 周期 Relay 与恢复边界细分

审查发现现有周期草稿尚不能交付：消费领先发布确认时原事件可能滞留处理中；未发布事件失败上限与人工恢复需按用户已确认策略修复；集群配置不能可靠表达全局暂停/恢复且禁用实例可能使旧作业阻止启动；周期测试必须独占隔离其扫描的数据库。先按以下叶节点分别验收、审查、提交、推送；每叶含状态文档最多8文件。草稿暂存本机临时目录，不计为已完成。

| 子节点 | 输出 | 前置与验收 |
|--------|------|------------|
| NODE-E03.5a1 | 消费凭据领先确认时的原事件状态及迁移 | 前置E03.4e；真实数据库验证处理中/重试/失败状态下消费成功、竞争和迟到确认；不得伪造发布确认，新增迁移不得修改旧迁移 |
| NODE-E03.5a2a | 有界对账存储契约与数据库游标/索引 | 前置E03.5a1；验证迁移和单次扫描结果契约，不修改既有事件 |
| NODE-E03.5a2b | 基于真实凭据的单项事务对账与隔离数据库夹具 | 前置E03.5a2a；独占测试库验证锁顺序、游标、凭据匹配、有效租约与迟到确认；分页有界 |
| NODE-E03.5a2c | 有界对账Application入口及组合验收 | 前置E03.5a2b；按扫描数限批，忙行或无效证据推进并在后续轮次重访；不依赖Broker，E03.5a2完成 |
| NODE-E03.5b | 未发布事件的故障重试与恢复 | 前置E03.5a2c，发布失败策略已由用户确认；验证Broker恢复、确定性失败和原事件顺序，不把消费者死信用于从未发布事件 |
| NODE-E03.5c | 集群暂停、恢复与实例禁用协调 | 前置E03.5b，集群状态治理已由用户确认；双实例混合版本/中途故障与禁用时其他任务继续，确保无无意恢复或虚假成功 |
| NODE-E03.5d | 周期Relay隔离验收与运行说明 | 前置E03.5c；使用本测试独占数据库验证真实Broker、消费顺序、缓存屏障、暂停恢复、隔离与退出，完成E03.5 |

##### NODE-E03.5b 发布失败与原事件恢复细分

按用户已确认的故障规则，先区分非法信封与Broker否定确认；否定确认可能由队列暂时满载造成，按不确定故障处理。每叶含状态文档最多8文件，分别验收、审查、提交和推送；必要时继续细分。

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E03.5b1 | 拆分明确非法信封与Broker否定确认的发布结果；单元测试验证分类，本叶不改变持久化重试行为 |
| NODE-E03.5b2 | 原事件临时故障封顶持续重试及明确故障失败；独占MariaDB验证重试、退避、饱和、锁与顺序，消费死信重放策略保持既有行为 |
| NODE-E03.5b3 | 原事件人工恢复的领域契约及持久回执；验证迁移、身份和幂等约束 |
| NODE-E03.5b4 | 原事件事务恢复及真实竞争测试；复核消费证据、聚合头和活动重放意图，保留事件身份与失败记录 |
| NODE-E03.5b5 | 管理员授权恢复入口及操作审计；权限实时生效，不允许替换原事件正文，必要时继续拆分 |
| NODE-E03.5b6 | 独占库及隔离Broker组合验收：临时故障恢复、明确故障人工恢复、同聚合顺序及跨聚合推进，完成E03.5b |

##### NODE-E03.5b5 管理员入口细分

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E03.5b5a | Application在同一工作单元内获取管理协调锁、实时核验可信管理员并恢复原事件；包括旧请求权限撤销与并发测试 |
| NODE-E03.5b5b | 按确认的接口路径与来源限制接入HTTP/网关，验证认证、管理授权、请求身份、错误与文档；必要时继续拆分 |

##### NODE-E03.5b5b 管理接口细分

用户已确认原事件恢复接口位于 `/api/management/outbox/...`，网关沿用既有管理来源CIDR，应用每次实时校验管理员；每叶含状态文件最多8个。

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E03.5b5b1 | 网关先将恢复管理路径纳入CIDR，验证直连IP、伪造代理头、编码路径及前缀边界；不开放恢复接口 |
| NODE-E03.5b5b2 | 版本化恢复HTTP入口及真实认证/管理员权限、幂等、错误协议与接口文档验收；完成E03.5b5b |

历史FAILED可能实际到达Broker；不得仅据状态推定从未投递，也不得自动批量恢复。重放与原事件恢复各自核验资格和幂等，避免并发产生重复发送路径；可重复投递仍依赖消费幂等。

##### NODE-E03.5c 集群暂停与实例禁用细分

共享数据库保存暂停状态及单调版本；首次初始化为暂停，实例启用不自动恢复。状态变更核验观察版本，旧版本不覆盖；批次准入与暂停在数据库锁上确定先后，已经准入的批次按实际结果收尾。禁用实例不领取Relay工作，其余任务继续。具体Quartz混合实例调度先验收现有机制，再在后续叶节点实现；旧二进制未具备新协议时不能宣称兼容。

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E03.5c1 | 共享暂停状态与版本的领域契约及独立迁移；验证单例、初始暂停与约束，不接入调度 |
| NODE-E03.5c2 | 共享状态存储及批次准入；真实数据库验证版本竞争、幂等、准入/暂停先后、回滚与上限 |
| NODE-E03.5c3 | Application提供显式状态更新和准入边界；验证不允许旧版本覆盖、事务隔离和错误传播 |
| NODE-E03.5c4 | 固定任务注册与禁用实例协调；保证其他任务继续并验证共享Quartz中的触发活性，必要时进一步拆分 |
| NODE-E03.5c5 | 多实例共享暂停、重启和执行中故障组合验收，完成E03.5c |

##### NODE-E03.5c4 Relay调度隔离细分

用户确认Relay使用同一数据库内独立Quartz调度组，只有本实例启用Relay时加入；普通任务继续使用既有调度组，共享暂停和ShedLock规则保持。以不同Quartz调度器名称隔离持久化命名空间，禁用实例不创建Relay调度器。新调度器先保持standby，完成目标登记及资格检查后才启动；旧二进制没有新协议时不得宣称具备新调度行为。

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E03.5c4a | 可选独立JDBC集群调度器装配，默认不启动Relay工作；真实数据库验证命名空间隔离、启用/禁用实例及普通任务继续 |
| NODE-E03.5c4b | 分离Relay固定注册与普通任务，显式核验持久化目标和配置；验证双实例、重启、未登记目标拒绝 |
| NODE-E03.5c4c | 接入共享暂停准入、Relay驱动及有界生命周期；禁用实例不领取，暂停前已准入批次完成，必要时细分 |
| NODE-E03.5c4d | 多实例调度隔离、故障与活性验收，完成E03.5c4 |

##### NODE-E03.5c4c Relay驱动与生命周期细分

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E03.5c4c1 | 同一生命周期协调普通和待机Relay调度器，先停止两组触发再关闭共享工作器；真实任务关闭及线程退出测试 |
| NODE-E03.5c4c2 | Relay调度驱动通过共享暂停状态准入；真实数据库验证暂停、旧版本与已准入任务，业务处理器仍不自动启动 |
| NODE-E03.5c4c3 | 显式启用的Relay处理器、边界配置及在目标核验后启动专用调度器；接入有界对账与发布，分别验证关闭及故障 |
| NODE-E03.5c4c4 | Relay调度、暂停恢复、实例禁用与真实Broker组合验收，完成E03.5c4c |

##### NODE-E03.5c4c2 共享暂停准入细分

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E03.5c4c2a | Relay工作器在领取任务之前通过共享暂停准入；真实数据库验证暂停拒领、恢复、已准入任务收尾及普通任务独立 |
| NODE-E03.5c4c2b | Relay Quartz驱动在计划登记和到期查询之前检查共享状态并核验所属调度器；双实例与锁忙失败关闭验收 |

##### NODE-E03.5c4c3 Relay处理器与启动细分

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E03.5c4c3a | 建立显式启用的Relay处理器与有界配置，组合有界对账和发布；验证输入、配置及中断，不启动调度器 |
| NODE-E03.5c4c3b | 在专用Quartz目标核验完成后启动Relay调度器，验证开关、关闭和启用/禁用实例并存 |

##### NODE-E03.5c5 共享暂停管理与收敛细分

共享暂停须可实际操作，管理入口沿用已确认的 `/api/management/outbox/...` 来源规则和当前启用管理员检查；请求携带观察版本，不能由启动配置覆盖。

| 子节点 | 输出与验收 |
|--------|------------|
| NODE-E03.5c5a | 管理员共享状态查询与变更应用入口；同事务复核权限及版本，验证撤权、锁竞争和旧版本 |
| NODE-E03.5c5b | 版本化暂停查询和更新HTTP接口；验证认证、管理员权限、旧版本、路径及OpenAPI |
| NODE-E03.5c5c | 多实例暂停、重启和在途故障组合验收，完成E03.5c |

E03.5a1/a2先修复可独立判定的一致性；其余叶节点依已确认规则顺序执行。历史SENT继续只表示确认，消费事实必须保留独立证据；不自动清理或覆盖用户已有数据库、消息拓扑及任务历史。部署节点仍暂缓。

用户已确认：连接不可用等临时发布故障按封顶间隔持续重试；明确失败保留FAILED并提供管理员手动恢复原事件，恢复后保持原事件身份和聚合顺序。集群暂停状态持久化且变更使用递增版本，旧版本不能覆盖；禁用Relay的实例不领取Relay工作，已开始的批次按实际结果收尾。
