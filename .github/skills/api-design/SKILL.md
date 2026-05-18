---
name: api-design
description: Stratum REST API 设计技能。当新增或调整 Interface/Query 端点、统一响应、版本控制、分页排序、幂等、OpenAPI 文档时启用。
---

# Stratum API 设计技能

## 何时启用

- 新增或调整 HTTP 端点
- 设计请求/响应 DTO、分页、排序、过滤
- 定义错误码、幂等键、版本控制
- 对齐 OpenAPI 3 与 Knife4j 文档

## 先读这些规范

- docs/CONSTITUTION.md
- docs/FORBIDDEN.md
- docs/API设计规范.md
- docs/项目结构.md

## Stratum 专属约束

- 版本控制只使用 `X-API-Version`，请求缺省值为 `1`。
- 写接口放在 `stratum-interface`；复杂读接口放在 `stratum-query`，并通过 `QueryGateway + QueryHandler` 实现。
- 响应必须符合统一模型：成功返回 `code/message/data/traceId`；失败返回 `code/message/details/traceId`。
- URL 使用复数资源名；只有非 CRUD 行为才允许动作型接口。
- 列表接口统一使用 `page/pageSize/sort`；`sort` 字段必须服务端白名单。
- 创建、扣减、支付、触发任务等接口必须评估 `Idempotency-Key`。
- OpenAPI 3 是唯一文档来源，Knife4j 仅作展示层。

## 设计步骤

1. 先判断接口属于写模型还是读模型，再决定落点模块。
2. 为请求体、分页参数、版本头和错误码定义明确契约。
3. 明确认证、授权、数据权限、限流与幂等要求。
4. 为返回体补齐 `traceId`、`details`、`page/pageSize/total/items` 等约束。
5. 同步更新注解、示例与 API 文档。

## 常见反例

- 在 URL 中使用 `get/create/update` 等动词。
- Query 端点直接执行写操作，或绕过 `QueryGateway`。
- 所有错误都返回 `200`，或者把内部异常明文暴露给客户端。
- 未限制 `sort` 白名单，导致任意字段排序。
- 新增接口后未更新 OpenAPI 注解或示例。

## 验证建议

- `gradlew :stratum-interface:build`
- `gradlew :stratum-query:build`
- 如接口已可启动：`gradlew :stratum-starter:bootRun`
- 启动后检查 `/v3/api-docs` 与 `/doc.html`
- 为新增接口至少覆盖成功、参数失败、权限失败三类场景