---
name: gateway-routing
description: Stratum 网关路由技能。当配置 Spring Boot Gateway 路由、限流、Header 灰度、追踪透传以及与项目外负载均衡的职责边界时启用。
---

# Stratum 网关路由技能

## 何时启用

- 新增或调整 Gateway 路由
- 配置限流、非法头过滤、请求体限制
- 落地按 Header 的灰度发布规则
- 对齐网关与项目外负载均衡的边界

## 先读这些规范

- docs/CONSTITUTION.md
- docs/FORBIDDEN.md
- docs/网关与负载均衡设计.md
- docs/技术架构.md
- docs/API设计规范.md
- docs/可观测性设计.md
- docs/容器化与部署.md

## Stratum 专属约束

- 网关实现固定为 Spring Boot Gateway。
- 负载均衡不在项目内实现；裸金属使用 Nginx，容器场景使用编排层 Service/Ingress。
- 所有外部请求必须先进入网关，再进入应用。
- 网关必须透传 `traceparent` 与 `X-Request-Id`。
- 网关层必须启用基础限流、非法头过滤、超大请求体拦截。
- 敏感管理接口必须限制来源网段。
- 灰度策略固定为按 Header 路由，且规则必须可回滚。

## 设计边界

- Gateway 负责入口治理、路由、限流、Header 透传和灰度。
- App 模块负责业务鉴权、业务处理和领域逻辑。
- 项目外负载均衡负责健康检查、节点摘除和流量分发。
- 不要在 Gateway 中实现数据库访问、业务规则或项目内负载均衡算法。

## 实现步骤

1. 先按域名或路径前缀定义路由归属。
2. 为每条路由补齐追踪透传、超时、限流和错误响应策略。
3. 对管理接口增加来源网段限制或额外安全控制。
4. 如需灰度，使用 Header 规则路由到新旧版本，并保留回滚开关。
5. 输出结构化访问日志，至少包含 `upstream、status、latencyMs、traceId、clientIp`。
6. 在部署文档中明确与 Nginx / Ingress 的职责边界，避免重复配置或相互覆盖。

## 审查清单

- 是否误把负载均衡职责落进项目代码？
- 是否所有对外入口都经过网关？
- 限流是否返回标准错误语义，而不是笼统 `500`？
- 是否透传了 `traceparent` 与请求 ID，便于端到端排障？
- 灰度规则是否支持一键回滚并保留稳定副本？

## 常见反例

- 在项目内写 upstream/节点选择算法，越过既定边界。
- 网关只转发，不做限流和非法头拦截。
- 灰度直接按实例硬编码，无法快速回退。
- 网关日志缺少 traceId，导致入口与应用日志无法关联。

## 验证建议

- `gradlew :stratum-gateway:build`
- 如果已接入 starter：`gradlew :stratum-starter:build`
- 为路由配置至少验证：正常转发、限流返回、灰度命中、追踪头透传
- 联调时额外确认上层 Nginx 或 Ingress 负责健康检查和摘除，而不是项目内组件