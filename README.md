# Stratum

Stratum 是采用 Java、Spring Boot 和 DDD 分层的多模块单体项目，以标准企业 ERP 的通用进销存为首期业务，按 SDD（规范驱动开发）逐步实现。

## 当前状态

Phase A/B、查询基架与接口基架已完成验收：包含 Domain/命令与工作单元、MariaDB/Easy-Query、Outbox可靠落盘、版本化缓存与分布式锁、查询路由、HTTP版本与统一响应、参数校验及OpenAPI/Knife4j。NODE-C03 已完成持久化幂等与物料、客户、供应商、仓库的创建/改名/删除写能力；库存已完成精确余额、不可变流水与内部过账，余额/流水/Outbox同事务提交；采购销售已接通订单创建与分批收货/出库，订单、履约、库存、流水、事件及幂等回执同事务提交，并有来源外键保护；只读侧已提供主数据、库存余额/流水、订单摘要/详情和履约历史的QueryGateway查询；主数据HTTP已接通创建、改名、删除、列表及详情，库存余额与流水HTTP查询已接通，采购销售订单创建与分批履约HTTP已接通，订单摘要、详情与履约历史HTTP查询已接通，后续继续整体闭环验收。详见 [当前状态](docs/CONTEXT.md)。

ERP业务闭环、消息投递与消费、认证与数据权限、网关、后台任务及完整可观测性尚未完成；部署相关节点本轮暂缓。设计文档中的目标不代表当前已交付。

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

启动后可访问 `/v3/api-docs` 获取生成文档、`/doc.html` 查看 Knife4j。当前未提供 Docker Compose 文件或 Prometheus 指标端点。完整的操作说明、验证边界与后续设计见 [开发与迭代](docs/development.md)。
