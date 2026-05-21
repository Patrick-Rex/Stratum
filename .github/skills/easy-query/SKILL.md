---
name: easy-query
description: EasyQuery ORM 技能。当任务涉及 EasyQuery/easy-query、@EntityProxy/@Table/@Navigate、EasyEntityQuery、queryable/toPageResult、insertable/updatable/deletable、分库分表、读写分离、DTO 结构化查询或 Java/Kotlin ORM CRUD 时启用。
---
# EasyQuery ORM 技能

## 目标

- 在 Stratum 中以本地规范优先的方式使用 EasyQuery，减少外部检索噪音与 token 消耗。
- 保持与技术架构一致：JDK 25、Spring Boot 4.0.5+、Gradle 9.4.1、DDD 分层约束、查询基架统一。
- 默认输出可执行方案（代码、配置、排查步骤），避免概念化回答。

## 何时启用

- 任务明确涉及 EasyQuery/easy-query 的建模、查询、CRUD、分页、分库分表、读写分离。
- 代码出现以下关键词：`@EntityProxy`、`@Table`、`@Navigate`、`EasyEntityQuery`、`queryable`、`toPageResult`、`insertable`、`updatable`、`deletable`。
- 需要排查 APT/KSP 代理生成、方言依赖、Starter 自动配置相关问题。

## 本地优先策略（MUST）

- 默认禁止主动打开官方文档与外部源码链接。
- 先用仓库内规则与现有代码完成方案：`docs/CONSTITUTION.md` -> `docs/CONTEXT.md` -> `docs/FORBIDDEN.md` -> 相关 skill。
- 仅当出现未定义或者无法裁决的情况时，才允许外查：
- 外查必须最小化：只查问题对应片段，禁止整站浏览式检索。

## 架构对齐约束（MUST）

- 技术基线：JDK 25、Spring Boot 4.0.5+、Gradle 9.4.1。
- 分层边界：
	- Domain 禁止依赖 EasyQuery API、Spring 组件、数据源或方言实现。
	- EasyQuery 相关实现应落在 Infrastructure，Application 仅编排用例和事务。
	- Interface 负责入参校验与协议转换，不承载持久化细节。
- 查询统一：分页、排序、过滤应通过 Query 基架对外暴露，避免 Interface 直接拼 ORM 细节。
- 事务边界：写用例事务仅在 Application 边界定义，不在 Repository 内扩散事务语义。

## 核心事实（本地常识）

- 框架定位：Java/Kotlin 强类型 ORM，支持隐式 join/子查询/分组、逻辑删除、乐观锁、分库分表、读写分离、DTO 结构化查询。
- 关键模块：`sql-core`、`sql-processor`、`sql-ksp-processor`、`sql-platform`、`sql-extension`、`sql-db-support`、`sql-test`。
- 实践优先级：`sql-test` 的示例通常比文档更贴近真实可运行用法。

## 依赖组合速查

- Spring Boot 4：`sql-springboot4-starter` + `sql-processor` + `sql-<db>`。
- Kotlin + Spring Boot 4：`sql-springboot4-starter` + `sql-ksp-processor` + `sql-<db>`。
- 非 Spring：`sql-api-proxy` + `sql-processor` + `sql-<db>`。
- 常见数据库方言：MySQL、PostgreSQL、Oracle、SQL Server、H2、SQLite、ClickHouse、达梦、KingbaseES、GaussDB、DuckDB、DB2。

## 仓库内落地规范

- 实体注解：
	- 使用 `@Table`、`@Column`、`@EntityProxy` 进行映射。
	- 逻辑删除、乐观锁等字段需在实体层显式标注，不在业务代码中硬编码约束。
- 仓储实现：
	- Repository 接口在 Domain，EasyQuery 实现在 Infrastructure。
	- 复杂查询拆为可复用私有片段，避免在单方法堆叠超长 DSL。
- 查询构建：
	- 动态筛选优先结构化条件对象，避免 Controller 层拼接字符串。
	- 分页统一使用 `toPageResult`，排序字段必须白名单。
- 写操作：
	- `insertable/updatable/deletable` 仅处理持久化动作，幂等与业务校验放 Application/Domain。
	- 批量写入要明确批次大小与失败重试策略，避免无界批处理。
- 多数据源与分片：
	- 仅在明确业务需求下启用分库分表。
	- 路由键定义必须稳定且可观测，禁止隐式随机路由。

## 源码定位最短路径

- 注解定义：`sql-core/src/main/java/com/easy/query/core/annotation/`
- 查询 API：`sql-core/src/main/java/com/easy/query/core/api/`
- 分库分表：`sql-core/src/main/java/com/easy/query/core/sharding/`
- APT 处理器：`sql-processor/src/main/java/`
- KSP 处理器：`sql-ksp-processor/src/main/java/`
- Starter 自动配置：`sql-extension/sql-springboot-starter/src/main/java/`
- 方言实现：`sql-db-support/sql-<db>/src/main/java/`
- 可运行示例：`sql-test/src/main/java/`

## 问题到路径映射

- `@Navigate` 行为不符合预期：先看 `sql-test` 的 navigate/entity 示例，再看文档 navigate 章节。
- `toPageResult` 计数或分页 SQL 异常：先看 `sql-test` 中复杂查询/分页测试，再对照 queryable 链式写法。
- 代理类未生成：检查 `@EntityProxy` 标注、`sql-processor` 或 `sql-ksp-processor` 是否正确接入。
- 方言差异导致 SQL 不兼容：查看对应 `sql-db-support/sql-<db>` 实现与测试。
- 分库分表路由异常：优先核对 `shardingInitializer`、`ShardingTableKey`/`ShardingDataSourceKey` 与 `sql-test/sharding` 示例。

## 禁止事项

- 禁止在 Domain 层引入 `com.easy.query` 相关依赖。
- 禁止在 Interface 层直接构建复杂 EasyQuery DSL。
- 禁止未做白名单校验的动态排序/动态列名直传。
- 禁止为了追求通用性提前抽象 Repository 基类，导致查询表达被隐藏和难维护。
- 禁止默认外查文档替代本地规则判断。

## 最小验证清单

- 编译验证：受影响模块可通过构建。
- SQL 行为验证：至少覆盖一个分页查询与一个写操作。
- 分层验证：Domain 不出现 EasyQuery 导入。
- 风险验证：动态排序字段白名单、分页参数边界、空条件处理已检查。

## 外查触发模板（仅故障时）

- 触发原因：本地规则无法裁决 / 版本行为不确定 / 内部实现异常 / 其他不可预见问题。
- 已完成本地排查：列出已检查文件与结论。
- 外查范围：仅一个问题点，最多两处来源。
- 备用入口：官方文档 https://www.easy-query.com/easy-query-doc/ ，源码仓库 https://github.com/dromara/easy-query 。
- 回收结论：将可复用规则写回本 skill，避免重复外查。

## 回答要求

- 所有说明使用中文。
- 优先给“可落地代码或配置”，避免只给概念。
- 涉及行为差异时，优先引用 `sql-test` 的同类示例进行结论校验。
- 未确认版本行为时，明确标注“需按当前 easy-query 版本复核”，避免猜测性结论。
- 输出时优先给本地规则结论，再给最小变更方案，最后给验证步骤。
