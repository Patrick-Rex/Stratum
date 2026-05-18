---
name: query-patterns
description: Stratum 查询基架技能。当设计或实现 QueryGateway、QuerySpec、QueryHandler、分页排序白名单、数据权限过滤与查询可观测性时启用。
---

# Stratum 查询基架技能

## 何时启用

- 新增读模型查询接口
- 设计或调整 `QueryGateway / QueryHandler / QuerySpec / QueryResult`
- 实现分页、排序、过滤、模糊查询与字段白名单
- 处理查询侧数据权限、脱敏与慢查询治理

## 先读这些规范

- docs/CONSTITUTION.md
- docs/FORBIDDEN.md
- docs/查询基架设计.md
- docs/API设计规范.md
- docs/项目结构.md
- docs/可观测性设计.md

## 固定结构

- 查询入口：`QueryGateway`
- 过滤与排序表达：`QuerySpec`
- 具体实现：`QueryHandler`
- 统一结果：`QueryResult`
- 暴露位置：复杂读接口放 `stratum-query`

## Stratum 专属约束

- 每个查询必须定义 `queryCode`、输入 DTO、排序白名单、分页策略、输出 DTO。
- 复杂列表查询必须通过 `QueryGateway` 路由，禁止直接从 Controller/Endpoint 访问仓储实现。
- `page` 从 `1` 开始，`pageSize` 默认 `20`，最大 `200`。
- `sort` 只允许服务端白名单字段；模糊查询也必须有字段白名单。
- Query 层只读，禁止任何 INSERT/UPDATE/DELETE。
- 查询侧必须接入数据权限过滤，敏感字段默认脱敏，非授权字段禁止返回。
- 任意动态条件必须参数化，禁止拼接 SQL。

## 实现步骤

1. 先定义查询场景的 `queryCode` 与输入 DTO。
2. 明确分页方式、排序字段白名单和过滤字段白名单。
3. 在 `QuerySpec` 中承载筛选、区间、模糊和排序规则。
4. 在 `QueryHandler` 中只做查询编排与结果组装，不引入写副作用。
5. 在 `QueryResult` 中统一返回 `page/pageSize/total/items`。
6. 补齐 `queryCode、durationMs、resultCount、traceId` 的日志或指标输出。

## 审查清单

- 是否所有排序字段都来自服务端白名单？
- 是否把数据权限过滤前置到了查询条件中？
- 是否限制了最大返回条数，避免大结果集直接打满接口？
- 是否对模糊查询、范围查询和空条件做了边界处理？
- 是否返回了不应暴露的内部字段或敏感字段？

## 常见反例

- 在 Query 模块里执行写库操作。
- 客户端传什么字段就按什么字段排序。
- 通过字符串拼接构造动态 SQL。
- 把复杂报表同步查询暴露成普通列表接口，而不是异步任务。
- 漏记 `queryCode` 或慢查询指标，导致问题不可观测。

## 验证建议

- `gradlew :stratum-query:build`
- `gradlew :stratum-query:test`
- 文本检查：`rg "INSERT|UPDATE|DELETE" stratum-query/src/main/java`
- 为新增查询至少覆盖分页、排序白名单、权限过滤、注入回归四类场景