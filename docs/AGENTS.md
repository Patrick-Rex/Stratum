# Stratum - SDD 可执行文档集

> 基于 DDD 架构的企业级单体应用脚手架，采用 SDD（规范驱动开发）方法落地。

## 1. 项目定位

Stratum 是一个单体可扩展的 Java Web 方案，核心目标是：
- 以业务规范驱动实现，而不是先写代码再补文档
- 通过明确的约束、验收标准和变更流程，确保团队协作一致
- 在保持单体部署效率的同时，具备水平扩展能力

## 2. 统一技术基线（已锁定）

| 类别 | 技术 | 版本/策略 |
|------|------|-----------|
| JDK | OpenJDK | 25 |
| 核心框架 | Spring Boot | 4.0.5+ |
| 构建工具 | Gradle | 9.4.1+ |
| 架构 | DDD | Interface -> Application -> Domain -> Infrastructure |
| 结构策略 | 多模块 | interface/application/domain/infrastructure/common/starter |
| 数据库 | MariaDB | 10.11+ |
| 缓存 | Redis | 开发单节点，生产 Sentinel |
| 消息队列 | RabbitMQ | 4.2.0+ |
| 后台任务 | Spring Scheduler + Quartz + ShedLock | - |
| 网关 | Nginx Gateway | 1.29+ |
| 配置中心 | Nacos | 3.2.0+ |
| 认证授权 | Spring Security + JWT + RBAC | 启用 token 撤销 |
| 可观测性 | OpenTelemetry + Prometheus + Loki + Grafana + Jaeger | 全链路追踪 |

## 3. 文档入口（执行顺序）

1. [SDD执行总览](SDD执行总览.md)
2. [技术架构](技术架构.md)
3. [项目结构](项目结构.md)
4. [基础设施配置](基础设施配置.md)
5. [安全认证设计](安全认证设计.md)
6. [API设计规范](API设计规范.md)
7. [查询基架设计](查询基架设计.md)
8. [工作单元与事务一致性设计](工作单元与事务一致性设计.md)
9. [消息总线设计](消息总线设计.md)
10. [后台任务设计](后台任务设计.md)
11. [网关与负载均衡设计](网关与负载均衡设计.md)
12. [横切关注点与AOP设计](横切关注点与AOP设计.md)
13. [可观测性设计](可观测性设计.md)
14. [容器化与部署](容器化与部署.md)
15. [Spec清单](Spec清单.md)
16. [AI可执行开发计划](AI可执行开发计划.md)

## 4. 快速执行

### 4.1 环境要求

- Docker Desktop（含 compose）
- JDK 25
- Gradle 9.4.1+

### 4.2 启动依赖

```bash
docker compose -f docker/compose/docker-compose.dev.yml up -d
```

### 4.3 本地运行

```bash
./gradlew clean test
./gradlew :stratum-starter:bootRun --args='--spring.profiles.active=local'
```

### 4.4 运行验收

- API 文档: http://localhost:8080/doc.html
- 健康检查: http://localhost:8080/actuator/health
- 指标端点: http://localhost:8080/actuator/prometheus

## 5. 文档治理规则

- 所有规范使用 MUST/SHOULD/MAY 语义。
- 本目录内出现冲突时，优先级如下：
  1) SDD执行总览
  2) 技术架构
  3) 专题规范（安全、API、可观测性等）
- 任何破坏性变更必须先更新文档，再进入代码实现。

## 6. 当前状态

- 文档状态：可执行
- 需求状态：已冻结第一阶段基线
- 下一步：按 SDD 执行总览进入任务拆解与实施
