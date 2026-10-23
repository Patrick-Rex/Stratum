# 开发与迭代

## 阅读与执行

通用规则见 [根目录 AGENTS](../AGENTS.md)，文档目录见 [文档导航](README.md)。进入开发节点前遵循 [CONSTITUTION](CONSTITUTION.md) 的入口协议：

1. 读取 CONSTITUTION，确认架构约束。
2. 读取 [CONTEXT](CONTEXT.md)，确认当前阶段、节点及阻塞项。
3. 读取 [FORBIDDEN](FORBIDDEN.md)，核对禁止项。
4. 读取 [Spec 清单](Spec清单.md)，定位任务对应规范；按需查阅 [术语表](GLOSSARY.md)。
5. 在 [开发计划](AI可执行开发计划.md) 中读取对应节点，再加载专题规范和相关仓库技能。

规范优先级由 CONSTITUTION 第 1 节定义：CONSTITUTION > FORBIDDEN > SDD 执行总览 > Spec 清单 > 专题规范 > 开发计划。CONTEXT 是状态记录，不覆盖架构规范。遇到无法依据已有决定解决的冲突，记录到 [偏移日志](entropy/DRIFT-LOG.md)，将需要决策的部分交给用户。

按用户指定任务实施。本轮依 [开发计划](AI可执行开发计划.md) 第 4 节逐节点验证、提交、推送后继续，已获用户授权；超过节点文件数限制时先登记子节点。执行开发节点时，核对前置依赖、范围和验收方式，遵循单节点文件数及主题限制；纯文档维护记录实际变更和验证，不推进节点状态。

## 当前工程基线

下表依据仓库构建文件核对于 2026-10-23，表示当前配置值，不表示本次完成了构建或运行验证。

| 项目 | 当前值 | 来源 |
|------|--------|------|
| Java Toolchain | JDK 25 | [根构建](../build.gradle) |
| Gradle Wrapper | 9.4.1 | [Wrapper 配置](../gradle/wrapper/gradle-wrapper.properties) |
| Spring Boot 插件 | 4.0.5 | 根构建；仅 starter 应用插件 |
| Spring Dependency Management 插件 | 1.1.7 | 根构建；仅 starter 应用插件 |
| Lombok | 1.18.44 | 根构建对所有子模块配置 compileOnly / annotationProcessor 及测试对应项 |
| 测试执行 | JUnit Jupiter 6.0.3 + Gradle Test | 根构建统一由 Spring Boot 4.0.5 BOM 管理 Jupiter 与 Platform Launcher；application 已有命令入口测试 |
| 子模块 | 9 个 | [settings.gradle](../settings.gradle) |

根构建还声明 SLF4J、Commons Lang、Jakarta Validation 的版本约束；版本约束本身不会引入依赖。不要据此宣称对应库或测试能力已经接入。新增依赖及插件版本集中维护，子模块不自行声明版本号。

当前实现范围：

| 模块 | 已有内容 |
|------|----------|
| stratum-common | ApiResponse、错误码、异常、traceId 上下文、基础扩展与帮助类、分页模型 |
| stratum-domain | Entity、AggregateRoot、ValueObject、Repository、DomainEvent |
| stratum-interface | ApiResponseFactory |
| stratum-application | Common 复用示例；Command / CommandHandler / UseCaseResult、UnitOfWork 与 OperationLog 契约及 CommandGateway 统一入口（NODE-B02） |
| stratum-query | Common 复用示例 |
| stratum-starter | 启动类、全局异常处理、国际化资源、Web 与 Actuator 依赖 |
| stratum-infrastructure | 已引入 Easy-Query 3.3.1（Boot 4 starter / MySQL 方言 / 处理器）、MariaDB JDBC、Flyway、Redis、Caffeine；已配置数据源与 Flyway；NODE-B03.2b 已验证真实 MariaDB 提交、回滚和嵌套事务 |
| stratum-gateway / stratum-job | Gradle 模块骨架 |

Domain 源码未导入框架，模块直接声明 common 依赖；根构建统一配置的 Lombok 编译期依赖同样作用于 Domain，不能将“源码无框架导入”表述为“所有配置均无第三方依赖”。

Easy-Query、MariaDB JDBC、Flyway、Redis、Caffeine 已接入依赖，已完成启动装配，事务行为验证见 NODE-B03.2b；RabbitMQ、Nacos、Quartz、ShedLock、认证、网关路由和观测组件仍为后续目标设计。选型约束见 [技术架构](技术架构.md) 和对应专题文档。

以下目标基线沿用原项目入口的既有约定，尚未接入时不作为当前依赖版本：

| 组件 | 目标版本 / 策略 |
|------|-----------------|
| Spring Boot | 4.0.5+（当前构建固定为 4.0.5） |
| MariaDB | 10.11+ |
| Redis | 开发单节点，生产 Sentinel |
| RabbitMQ | 4.2.0+ |
| Nacos | 3.2.0+ |
| 网关 | Spring Boot Gateway 4.0.5+；项目外负载均衡 |
| 后台任务 | Spring Scheduler + Quartz + ShedLock |
| 认证 | Spring Security + JWT + RBAC，启用 token 撤销 |
| 可观测性 | OpenTelemetry + Prometheus + Loki + Grafana + Jaeger |

## 环境与命令

在仓库根目录执行命令，优先使用 Wrapper。PowerShell 使用 `.\gradlew.bat`；Bash 使用 `./gradlew`。需要可用的 JDK 25；Wrapper 和依赖首次解析需要访问相应下载源。

```powershell
# 核对 Wrapper / JVM 与模块注册
.\gradlew.bat --no-daemon --version
.\gradlew.bat --no-daemon projects

# 按变更范围选择模块，只有跨模块改动才扩大验证范围
.\gradlew.bat --no-daemon :stratum-domain:build
.\gradlew.bat --no-daemon :stratum-starter:build

# 查看依赖方向
.\gradlew.bat --no-daemon :stratum-domain:dependencies --configuration runtimeClasspath
```

集成测试通过独立任务显式运行，不在普通 `test` 中隐式连接外部服务。先在本机 MariaDB 建立专用 `stratum_it` 数据库并授予测试账号权限，再设置 `STRATUM_IT_DB_URL`（`jdbc:mariadb://127.0.0.1:端口/stratum_it`）、`STRATUM_IT_DB_USERNAME`、`STRATUM_IT_DB_PASSWORD`，同时用 `STRATUM_IT_REDIS_PORT` 指定本机测试 Redis（认证时另设 `STRATUM_IT_REDIS_PASSWORD`），执行：

```powershell
.\gradlew.bat --no-daemon :stratum-starter:integrationTest
```

缺失连接配置会失败，不会静默跳过；仅测试类路径加载 `db/testmigration`，不会向应用迁移目录加入测试表。测试使用随机标识，只清理自己的记录。

这些是操作命令，不是本次执行记录。历史节点验收见 CONTEXT，本次实际验证写入 SESSION-HANDOFF。

## 本地启动与退出

当前 Starter 已接入 MariaDB 数据源、Flyway 与 Redis 客户端；运行需要可访问的 MariaDB 数据库与 Redis。Nacos 尚未接入，已移除不可解析的导入占位。

在同一 PowerShell 终端设置自己的连接信息，不将密码提交到仓库。默认数据库地址为 `jdbc:mariadb://127.0.0.1:13307/stratum`，用户为 `stratum`；密码必须由环境变量提供。

```powershell
$env:STRATUM_DB_URL = 'jdbc:mariadb://127.0.0.1:13307/stratum'
$env:STRATUM_DB_USERNAME = 'stratum'
$env:STRATUM_DB_PASSWORD = Read-Host '本地数据库密码' -MaskInput
$env:STRATUM_REDIS_HOST = '127.0.0.1'
$env:STRATUM_REDIS_PORT = '6379'
# Redis 启用认证时，另设 STRATUM_REDIS_PASSWORD。
.\gradlew.bat --no-daemon :stratum-starter:bootRun
```

`application.yml` 默认 local profile、8080 端口，Flyway 从 Infrastructure 的 `db/migration` 读取迁移。初始迁移仅登记版本，不创建业务表；后续按节点新增迁移，不依赖 ORM Code First 建表。连接池最多 10 个连接，禁用 Flyway clean。

成功启动后在另一终端执行：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

健康状态包含数据库与 Redis。本地 Caffeine 客户端默认最多 10000 条、写入后 30 秒失效，可用 `stratum.cache.local.maximum-size` 和 `stratum.cache.local.ttl` 调整；Cache Aside、空值保护、single-flight、分布式锁及补偿属于 NODE-B05，不以客户端连通性代替验收。当前尚无 OpenAPI / Knife4j、Prometheus 注册表或追踪后端，不将 `/doc.html`、`/v3/api-docs`、`/actuator/prometheus` 列为可用端点。

当前没有 `docker/compose/`，本轮不实施部署编排。可以连接现有 Docker 服务，也可创建独立测试容器；不要复用其他项目的业务库或清空共享 Redis。集成测试使用独立数据库及带 `stratum` 前缀的键。

结束时在启动终端按 `Ctrl+C`，确认应用退出并清除当前终端密码变量。自动化验证在 finally 中关闭自己启动的进程或容器，保留用户已有服务。

## 实现与验证

- 优先复用 common 已有基础能力；保持 Domain 不依赖技术框架，事务只在 Application 写用例边界开启，其他分层约束见 [项目结构](项目结构.md)。
- Java 开发按需读取 [编码规范](../.github/skills/coding-standards/SKILL.md)：优先稳定 JDK 特性、不可变模型和构造器注入；Lombok 禁止 `@Data`，纯数据模型按白名单使用 `@Getter` / `@Builder`，Domain 优先显式构造及工厂。
- 仅运行能验证本次变更的检查。编译查看 error 和退出码；测试读取失败详情和通过/失败数量。application 已提供命令路由及工作单元边界测试；其他模块没有测试时，Gradle `build` 成功仍不能作为行为覆盖证据。
- 文档变更核对本地链接、路径、命令前提、已实现与规划状态、UTF-8 编码、BOM、行尾和末尾换行，并执行 `git diff --check`；无需启动应用或构建。
- 仓库当前没有 `.editorconfig`；新增 Markdown 使用 UTF-8 无 BOM、LF 和末尾换行，已有文件保留一致的既有行尾。脚本格式遵循 [.gitattributes](../.gitattributes)。
- 交付前同步受影响文档，按 [AGENTS](../AGENTS.md) 更新 CONTEXT 和 SESSION-HANDOFF。报告实际检查结果、未解决项和下一节点，历史验收不改写为本次验收。

Lombok 升级到 1.18.44 以覆盖 JDK 25 兼容修复，依据 [官方变更记录](https://projectlombok.org/changelog)；项目沿用其 MIT 许可，不引入商业依赖。

NODE-B03.1 采用 Spring Boot 4.0.5 BOM 固定运行依赖，避免 ORM starter 引入不同 Boot 版本。Easy-Query 3.3.1 父 POM 为 Apache-2.0；Spring、Flyway Community、Caffeine 为 Apache-2.0，MariaDB Connector/J 为 LGPL-2.1。使用社区免费能力，不启用商业扩展。Boot BOM 当前管理 Flyway 11.14.1、MariaDB JDBC 3.5.7、Caffeine 3.2.3；以 Gradle 实际解析结果为准。
