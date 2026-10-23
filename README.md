# Stratum

Stratum 是采用 Java、Spring Boot 和 DDD 分层的多模块单体应用脚手架，通过 SDD（规范驱动开发）逐步实现查询、事务、消息、认证和运维能力。

## 当前状态

仓库已建立 9 个 Gradle 子模块、Starter 启动骨架、公共响应与异常处理、基础工具及 Domain 核心抽象。按现有验收记录，Phase A 与 NODE-B01 已完成，NODE-B02 已建立统一命令入口并通过行为测试，下一节点为 NODE-B03（持久化骨架）。详见 [当前状态](docs/CONTEXT.md)。

持久化与缓存客户端已接入；事务一致性、缓存治理、消息、认证、网关路由、查询基架和容器化仍需按后续节点验收；设计文档中的能力与验收项不代表当前已交付。

## 文档入口

| 目的 | 入口 |
|------|------|
| 开发协作规则 | [AGENTS.md](AGENTS.md) |
| 环境、实际技术基线、构建与运行 | [开发与迭代](docs/development.md) |
| 查找设计与流程文档 | [文档导航](docs/README.md) |
| 接续开发 | [当前状态](docs/CONTEXT.md)、[最近交接](docs/entropy/SESSION-HANDOFF.md) |
| 查看模块及依赖 | [项目结构](docs/项目结构.md) |

## 本地开发

使用 JDK 25 和仓库自带 Gradle Wrapper（9.4.1），无需另装 Gradle。先按 [开发与迭代](docs/development.md) 设置 MariaDB 与 Redis 连接参数及数据库密码，再在仓库根目录的 PowerShell 中执行：

```powershell
.\gradlew.bat --no-daemon projects
.\gradlew.bat --no-daemon :stratum-starter:build
.\gradlew.bat --no-daemon :stratum-starter:bootRun
```

当前使用环境变量配置本地依赖，Nacos 尚未接入。启动成功后可检查 `http://localhost:8080/actuator/health`，结束时按 `Ctrl+C` 退出。

当前未提供 Docker Compose 文件、`/doc.html` 或 Prometheus 指标端点。完整的操作说明、验证边界与后续设计见 [开发与迭代](docs/development.md)。
