---
name: security-review
description: Stratum 安全审查技能。当处理认证授权、输入校验、敏感数据、网关暴露、JWT、数据权限或密钥配置时启用。
---

# Stratum 安全审查技能

## 何时启用

- 新增认证、授权、数据权限、租户隔离相关能力
- 暴露新 API、网关路由或后台任务入口
- 处理 JWT、刷新令牌、会话撤销、签名密钥
- 接入外部 API、消息、文件上传或敏感数据存储

## 先读这些规范

- docs/CONSTITUTION.md
- docs/FORBIDDEN.md
- docs/安全认证设计.md
- docs/API设计规范.md
- docs/可观测性设计.md
- docs/网关与负载均衡设计.md

## Stratum 安全基线

- JWT 使用 `RS256`，Access Token `15` 分钟，Refresh Token `14` 天且必须旋转。
- 必含 claims 至少包括 `sub、iat、exp、jti、sid、ver`。
- Refresh 白名单、会话状态、用户版本必须进入 Redis 权威存储。
- `401` 只表示未认证或令牌无效；`403` 只表示已认证但无权限或数据范围拒绝。
- 查询必须附加 RBAC 与数据范围过滤；写操作必须做目标对象范围校验。
- 私钥、数据库口令、第三方密钥只允许来自 Nacos 或环境变量，禁止硬编码。
- 日志、异常、审计事件中禁止输出明文密码、令牌、私钥、完整身份证号等敏感数据。
- 网关与接口层应明确限流、鉴权透传、traceId 透传和安全事件记录。

## 审查清单

- 输入是否做 Bean Validation、枚举白名单、分页排序白名单？
- 是否存在 SQL 拼接、动态脚本拼接、路径穿越或命令注入风险？
- 是否正确区分 `401 / 403 / 404 / 409` 语义？
- 是否补齐 `AUTH_LOGIN_*`、`AUTH_FORBIDDEN`、`AUTH_DATA_FORBIDDEN` 等安全事件？
- 是否验证了 `logout-all`、改密、封禁后的 token 立即失效？
- 是否避免把内部异常、栈信息、密钥内容返回给客户端？

## 常见反例

- 把 JWT 私钥写入源码或测试常量并提交。
- 只做 RBAC，不做数据范围过滤。
- Query 接口按客户端传入的任意字段排序或过滤。
- 认证失败与权限失败统一返回 `500` 或 `200`。
- 记录包含 `password`、`Authorization`、`Set-Cookie` 的原始日志。

## 验证建议

- 为认证失败、权限失败、数据范围拒绝、token 撤销分别补测试
- 受影响模块优先运行 `gradlew :stratum-starter:test` 或对应模块 `test`
- 抽样检查配置文件、源码与日志模板中不存在硬编码密钥或敏感字段回显