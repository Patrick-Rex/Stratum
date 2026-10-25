# Stratum

Stratum 是采用 Java、Spring Boot 和 DDD 分层的多模块单体项目，以标准企业 ERP 的通用进销存为首期业务，按 SDD（规范驱动开发）逐步实现。

## 当前状态

Phase A/B/C已完成：具备分层命令与工作单元、MariaDB/Easy-Query、Outbox可靠落盘、版本化缓存与分布式锁、QueryGateway、统一HTTP协议及OpenAPI/Knife4j。ERP已实现四类主数据、采购销售订单、分批收货/出库和库存余额/流水；写入与事件、幂等回执同事务，完整HTTP业务闭环已验收。

Phase D已实现本地账号、环境初始化、BCrypt、RS256登录/刷新、Redis会话撤销、账号角色管理及运行时API鉴权。初始化管理员通过真实角色拥有全部已登记权限；普通账号默认无业务权限。主数据读写、采购销售读取/创建/履约及库存读取分别检查实时权限；`/auth/me`下发本人FEAT功能权限。授权撤回后不能继续重放写入回执，启停和成员关系变更撤销旧会话，并保护最后一名可用管理员。

2026-10-25的D02.4全工程验收重新执行33个任务，130项单元和367项真实MariaDB/Redis/HTTP集成测试全部通过，0失败、错误或跳过；D02已完成。仓库数据权限、消息投递消费、网关、后台任务及完整可观测性仍待实现；部署相关节点暂缓。准确节点状态见[CONTEXT](docs/CONTEXT.md)，历史验收不代表本次重新执行。

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

当前通过环境变量配置MariaDB、Redis和外部RSA密钥，初始管理员凭据不设默认值；Nacos尚未接入。未配置JWT时ERP端点返回401。启用认证后使用`/auth/login`与`/auth/refresh`，业务请求携带`Authorization: Bearer`；本人退出为`/auth/logout`、全部退出为`/auth/logout-all`。管理员通过`/api/auth/accounts`、`/api/auth/roles`及`/api/auth/permissions`管理账号与角色。启动后可检查`http://localhost:8080/actuator/health`，结束时按`Ctrl+C`退出。

启动后可访问 `/v3/api-docs` 获取生成文档、`/doc.html` 查看 Knife4j。当前未提供 Docker Compose 文件或 Prometheus 指标端点。完整的操作说明、验证边界与后续设计见 [开发与迭代](docs/development.md)。
