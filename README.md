# Stratum

Stratum 是采用 Java、Spring Boot 和 DDD 分层的多模块单体项目，以标准企业 ERP 的通用进销存为首期业务，按 SDD（规范驱动开发）逐步实现。

## 当前状态

Phase A/B/C已完成：具备分层命令与工作单元、MariaDB/Easy-Query、Outbox可靠落盘、版本化缓存与分布式锁、QueryGateway、统一HTTP协议及OpenAPI/Knife4j。ERP已实现四类主数据、采购销售订单、分批收货/出库和库存余额/流水；写入与事件、幂等回执同事务，完整HTTP业务闭环已验收。

Phase D已实现本地账号、环境初始化、BCrypt、RS256登录/刷新、Redis会话撤销、账号角色管理及运行时API鉴权。初始化管理员通过真实角色拥有全部已登记权限；普通账号默认无业务权限。主数据读写、采购销售读取/创建/履约及库存读取分别检查实时权限；`/auth/me`下发本人FEAT功能权限。授权撤回后不能继续重放写入回执，启停和成员关系变更撤销旧会话，并保护最后一名可用管理员。

D03已完成角色仓库管理与业务读写范围：ALL或指定仓库按多角色取并集；administrator固定全部、普通角色默认无仓库。读取在数据库计数/分页/详情前过滤；创建和履约在命令及回执重放前检查范围。API权限独立，只有履约API及范围也可收发货；无范围创建403、原订单目标404。同一会话立即看到范围变更，旧回执不能绕过撤权。

2026-10-26的E01.4全工程验收重新执行39个任务，193项单元、446项Starter显式集成和10项Gateway真实集成测试共649项全部通过，0失败、错误或跳过。消息基础设施E01已完成Topic/Quorum、原生重试/DLQ及Outbox专用发布确认；事务外有界Relay批次及5项真实投递故障组合验收已完成，消费去重/顺序存储与缓存补偿、ERP留痕应用用例已实现，Broker监听已接入显式开关，真实有序消费与跨实例重投去重组合已验收，死信持久化/重放和自动调度继续后续节点。D04.1及Phase D非部署范围已完成。独立网关已提供固定路由/Header灰度、协议透传、代理超时/错误、头体防护、管理来源CIDR、Redis共享限流、结构化访问日志及受保护Prometheus指标；消息死信恢复、后台任务及完整可观测性仍待实现；部署相关节点暂缓。准确节点状态见[CONTEXT](docs/CONTEXT.md)，历史验收不代表本次重新执行。

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

消息连接默认关闭；设置`STRATUM_MQ_ENABLED=true`时须显式提供`STRATUM_MQ_VHOST/USERNAME/PASSWORD`，地址用`STRATUM_MQ_HOST/PORT`。启用后会连接RabbitMQ 4.3+、校验版本并声明缓存补偿与ERP事件留痕的Topic/Quorum及DLQ拓扑；Outbox专用发布适配已具备持久JSON、关联confirm及mandatory返回判定，已提供显式Relay批次入口；消息连接启用后，另设`STRATUM_MQ_CONSUMER_ENABLED=true`才启动消费，消费默认关闭，自动Relay调度仍待后续节点；详见[开发与迭代](docs/development.md)。

当前通过环境变量配置MariaDB、Redis和外部RSA密钥，初始管理员凭据不设默认值；Nacos尚未接入。未配置JWT时ERP端点返回401。启用认证后使用`/auth/login`与`/auth/refresh`，业务请求携带`Authorization: Bearer`；本人退出为`/auth/logout`、全部退出为`/auth/logout-all`。管理员通过`/api/auth/accounts`、`/api/auth/roles`及`/api/auth/permissions`管理账号与角色，通过`/api/auth/roles/{id}/warehouse-scope`的GET/PUT读取和配置角色仓库范围。启动后可检查`http://localhost:8080/actuator/health`，结束时按`Ctrl+C`退出。

Gateway与Starter分别运行，默认监听`127.0.0.1:8081`，无需业务数据库凭据；监听地址/端口通过`STRATUM_GATEWAY_ADDRESS`、`STRATUM_GATEWAY_PORT`配置。先用`STRATUM_GATEWAY_REDIS_HOST/PORT/PASSWORD`提供可用Redis连接，再另开终端启动：

```powershell
$env:STRATUM_GATEWAY_ENABLED='true'
$env:STRATUM_GATEWAY_UPSTREAM='http://127.0.0.1:8080'
.\gradlew.bat --no-daemon :stratum-gateway:bootRun
# 在另一终端检查健康与本机指标
Invoke-WebRequest http://127.0.0.1:8081/actuator/health
Invoke-WebRequest http://127.0.0.1:8081/actuator/prometheus
```

未启用路由时不转发ERP/API文档。灰度由`STRATUM_GATEWAY_GRAY_ENABLED`、`STRATUM_GATEWAY_GRAY_UPSTREAM`及精确Header配置，关闭并重启回到稳定上游。已设置连接/响应超时、有界连接池和头体/缓冲上界，业务响应原样透传；管理账号/角色/权限及指标路径受直连CIDR限制，默认只允许IPv4/IPv6回环，可通过`STRATUM_GATEWAY_MANAGEMENT_ALLOWED_CIDRS`配置完整列表。客户端代理来源头不能获得管理资格。

转发按直连IP使用跨实例、跨稳定/灰度共享Redis令牌桶，默认20/s补充、40突发，超额429并返回Retry-After；Redis故障或共享状态不一致拒绝转发。所有入口实例需使用相同命名空间和策略。访问日志输出JSON，指标标签保持有界；完整参数、时延/取消语义和验收边界见[开发与迭代](docs/development.md)。结束各进程时分别按`Ctrl+C`退出。

启动后可访问 `/v3/api-docs` 获取生成文档、`/doc.html` 查看 Knife4j。Gateway已提供上述受保护指标端点；外部观测服务与Docker Compose仍暂缓，尚未对Starter提供同等指标入口。完整的操作说明、验证边界与后续设计见 [开发与迭代](docs/development.md)。
