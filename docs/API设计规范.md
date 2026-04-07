# API设计规范

## 1. 总则

- 风格：RESTful
- 版本：Header 版本控制
- 响应：统一响应模型
- 可观测：每次响应必须返回 traceId

## 2. 版本控制

- Header 名称：X-API-Version
- 缺省值：1
- 不支持版本：返回 400 + 版本错误码

## 3. URL 规则

- 资源名用复数名词
- 层级关系用路径表达
- 动作型接口仅用于非 CRUD 语义

## 4. 统一响应模型

成功：
- code: 0
- message: OK
- data: 任意对象
- traceId: 必填

失败：
- code: 非 0
- message: 错误说明
- details: 必填（无明细时返回空数组）
- traceId: 必填

## 5. 错误码段约定

- 400xx：参数与协议错误
- 401xx：未认证
- 403xx：无权限或数据权限拒绝
- 404xx：资源不存在
- 409xx：状态冲突或幂等冲突
- 500xx：服务内部错误

## 6. 分页与排序

- page 从 1 开始
- pageSize 默认 20，最大 200
- sort 格式：field,asc|desc
- sort 字段必须在服务端白名单中

## 6.1 查询基架约束

- 所有复杂列表查询必须通过 QueryGateway 进入 QueryHandler
- 每个查询接口必须声明 queryCode
- 查询响应必须返回 page、pageSize、total、items

## 7. 幂等规范

- 涉及创建/支付/扣减库存等接口必须支持 Idempotency-Key
- 服务端必须存储幂等结果并按 key 返回同结果

## 7.1 异步任务接口约束

- 触发后台任务的接口必须立即返回 taskId
- 任务状态查询接口必须返回 status、progress、lastError、traceId
- 任务取消接口必须幂等

## 8. 安全规范

- Authorization: Bearer <token>
- 敏感字段禁止明文回传
- 非法访问必须返回标准错误响应

## 9. 文档规范

- OpenAPI 3 为唯一接口文档来源
- Knife4j 仅作为展示层
- 新增接口必须同步更新注解与示例
- 必须暴露 OpenAPI JSON 端点（默认 `/v3/api-docs`）
- 必须提供 Knife4j UI 访问入口（默认 `/doc.html`）

## 10. 可执行验收

- 所有 Controller 响应符合统一模型
- traceId 在响应体中存在
- OpenAPI 与实际实现一致
- `/v3/api-docs` 可访问且返回有效 OpenAPI 文档
- `/doc.html` 可访问且能展示最新接口定义
- 幂等接口通过重复请求测试
