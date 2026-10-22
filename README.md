# Stratum

Stratum 是采用 Java、Spring Boot 和 DDD 分层的多模块单体应用脚手架，通过 SDD（规范驱动开发）逐步实现查询、事务、消息、认证和运维能力。

## 当前状态

仓库已建立 9 个 Gradle 子模块、Starter 启动骨架、公共响应与异常处理、基础工具及 Domain 核心抽象。按现有验收记录，Phase A 与 NODE-B01 已完成，NODE-B02 已建立统一命令入口并通过行为测试，下一节点为 NODE-B03（持久化骨架）。详见 [当前状态](docs/CONTEXT.md)。

数据库、缓存、消息、认证、网关路由、查询基架和容器化仍属于后续实现范围；设计文档中的能力与验收项不代表当前已交付。

## 文档入口

| 目的 | 入口 |
|------|------|
| 开发协作规则 | [AGENTS.md](AGENTS.md) |
| 环境、实际技术基线、构建与运行 | [开发与迭代](docs/development.md) |
| 查找设计与流程文档 | [文档导航](docs/README.md) |
| 接续开发 | [当前状态](docs/CONTEXT.md)、[最近交接](docs/entropy/SESSION-HANDOFF.md) |
| 查看模块及依赖 | [项目结构](docs/项目结构.md) |

## 本地开发

使用 JDK 25 和仓库自带 Gradle Wrapper（9.4.1），无需另装 Gradle。以下命令在仓库根目录的 PowerShell 中执行：

```powershell
.\gradlew.bat --no-daemon projects
.\gradlew.bat --no-daemon :stratum-starter:build
.\gradlew.bat --no-daemon :stratum-starter:bootRun --args="--spring.profiles.active=local --spring.config.import=optional:classpath:/application-local.yml"
```

当前配置保留 Nacos 导入占位，但尚未引入 Nacos 客户端；以上启动命令临时覆盖该占位，供本地骨架验证使用。启动成功后可检查 `http://localhost:8080/actuator/health`，结束时按 `Ctrl+C` 退出。

当前未提供 Docker Compose 文件、`/doc.html` 或 Prometheus 指标端点。完整的操作说明、验证边界与后续设计见 [开发与迭代](docs/development.md)。
