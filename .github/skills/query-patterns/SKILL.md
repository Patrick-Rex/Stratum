---
name: query-patterns
description: 'Stratum 查询基架工作流技能。USE FOR: QueryGateway、QuerySpec、QueryHandler、QueryResult、分页排序白名单、字段过滤白名单、数据权限过滤、敏感字段脱敏、查询可观测性。DO NOT USE FOR: 写模型事务、领域规则编排、Outbox 投递、网关路由。设计类文档优先使用 rg 精准检索命中条目，避免整篇通读。'
---

# Query Patterns

## When to Use

- 新增或调整读模型查询接口
- 设计或实现 QueryGateway、QuerySpec、QueryHandler、QueryResult
- 引入分页、排序白名单、过滤白名单、模糊查询
- 增加查询侧数据权限过滤、敏感字段脱敏、慢查询治理

## When Not to Use

- 写模型事务边界与命令侧编排
- Outbox 事件落盘与 Relay 投递
- 网关路由、鉴权前置、流量治理

## Required Inputs

- queryCode
- 输入 DTO（分页、排序、过滤）
- 排序白名单与过滤字段白名单
- 输出 DTO 与 QueryResult 结构
- 数据权限策略与脱敏规则

## Procedure

1. Collect constraints with targeted search first.
	- 先读 docs/CONSTITUTION.md 与 docs/FORBIDDEN.md。
	- 设计类文档默认使用 rg 精准命中，避免整篇读取。
	- 示例：rg "queryCode|page 从|pageSize|白名单|数据权限|脱敏|QueryGateway|QuerySpec|QueryHandler|QueryResult" docs/查询基架设计.md
2. Define query contract.
	- 必须定义 queryCode、输入 DTO、分页策略、排序白名单、输出 DTO。
3. Build QuerySpec and whitelist rules.
	- page 从 1 开始。
	- pageSize 默认 20，最大 200。
	- sort 仅允许服务端白名单字段。
	- 模糊查询必须有字段白名单。
4. Implement with QueryGateway to QueryHandler routing.
	- 复杂列表查询必须通过 QueryGateway 路由到 QueryHandler。
	- 禁止在 Controller/Endpoint 直接访问仓储实现。
5. Enforce query-side security.
	- 查询侧必须接入数据权限过滤。
	- 敏感字段默认脱敏，非授权字段禁止返回。
6. Keep query side read-only and parameterized.
	- Query 层只读，禁止 INSERT/UPDATE/DELETE。
	- 动态条件必须参数化，禁止拼接 SQL。
7. Add observability.
	- 输出 queryCode、durationMs、resultCount、traceId。

## Fixed Architecture Mapping

- 查询入口：QueryGateway
- 过滤与排序表达：QuerySpec
- 具体实现：QueryHandler
- 统一结果：QueryResult
- 模块落位：复杂读接口放在 stratum-query

## Validation Checklist

- 是否定义了 queryCode、输入 DTO、输出 DTO 与分页策略？
- 是否所有排序字段都来自服务端白名单？
- 是否对模糊查询和范围查询配置了字段白名单与边界保护？
- 是否将数据权限过滤前置到查询条件中？
- 是否对敏感字段完成脱敏并阻断非授权返回？
- 是否保证 Query 层无任何写操作与 SQL 拼接？
- 是否记录了 queryCode、durationMs、resultCount、traceId？

## Common Anti-patterns

- 在 Query 模块执行写库操作
- 客户端传入任意字段即允许排序
- 通过字符串拼接构造动态 SQL
- 将复杂报表同步查询暴露为普通列表接口
- 未输出 queryCode 或慢查询指标导致不可观测

## Quick Verification Commands

- gradlew :stratum-query:build
- gradlew :stratum-query:test
- rg "INSERT|UPDATE|DELETE" stratum-query/src/main/java
- rg "queryCode|durationMs|resultCount|traceId" stratum-query/src/main/java

## Deliverables

- 变更说明：查询链路与模块落位
- 约束清单：分页、排序白名单、过滤白名单、权限与脱敏
- 验证结果：构建、测试、静态检索命中
- 风险备注：大结果集、慢查询、越权字段暴露