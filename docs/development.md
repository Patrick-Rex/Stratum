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
| Lombok | 1.18.38 | 根构建对所有子模块配置 compileOnly / annotationProcessor 及测试对应项 |
| 测试执行 | Gradle Test + useJUnitPlatform() | 根构建；目前未发现 src/test 测试源码 |
| 子模块 | 9 个 | [settings.gradle](../settings.gradle) |

根构建还声明 SLF4J、Commons Lang、Jakarta Validation 和 JUnit Jupiter 的版本约束；版本约束本身不会引入依赖。不要据此宣称对应库或测试能力已经接入。新增依赖及插件版本集中维护，子模块不自行声明版本号。

当前实现范围：

| 模块 | 已有内容 |
|------|----------|
| stratum-common | ApiResponse、错误码、异常、traceId 上下文、基础扩展与帮助类、分页模型 |
| stratum-domain | Entity、AggregateRoot、ValueObject、Repository、DomainEvent |
| stratum-interface | ApiResponseFactory |
| stratum-application | Common 复用示例；Command / CommandHandler / UseCaseResult、UnitOfWork 与 OperationLog 契约（NODE-B02.1） |
| stratum-query | Common 复用示例 |
| stratum-starter | 启动类、全局异常处理、国际化资源、Web 与 Actuator 依赖 |
| stratum-gateway / stratum-infrastructure / stratum-job | Gradle 模块骨架 |

Domain 源码未导入框架，模块直接声明 common 依赖；根构建统一配置的 Lombok 编译期依赖同样作用于 Domain，不能将“源码无框架导入”表述为“所有配置均无第三方依赖”。

Easy-Query、Flyway、MariaDB、Redis、RabbitMQ、Nacos、Quartz、ShedLock、认证、网关路由和观测组件属于已确认的目标设计，尚未在当前模块中接入。选型约束见 [技术架构](技术架构.md) 和对应专题文档。

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

这些是操作命令，不是本次执行记录。历史节点验收见 CONTEXT，本次实际验证写入 SESSION-HANDOFF。

## 本地启动与退出

[application.yml](../stratum-starter/src/main/resources/application.yml) 默认使用 local profile 和 8080 端口，并暴露 Actuator 的 health、info。Nacos 配置导入仍为占位，starter 尚未引入对应客户端；进行不依赖 Nacos 的本地骨架检查时，通过命令行临时覆盖导入项：

```powershell
.\gradlew.bat --no-daemon :stratum-starter:bootRun --args="--spring.profiles.active=local --spring.config.import=optional:classpath:/application-local.yml"
```

上述 classpath 资源允许缺省，仅用于本地覆盖 Nacos 占位，不表示仓库已存在该文件。接入 Nacos 后应按 [基础设施配置](基础设施配置.md) 验证真实配置加载。

启动成功后，在另一个终端检查：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

当前未接入 OpenAPI / Knife4j、Prometheus 注册表及追踪后端，不将 `/doc.html`、`/v3/api-docs`、`/actuator/prometheus` 列为已可用端点。

当前仓库没有 `docker/compose/` 文件；[容器化与部署](容器化与部署.md) 中的拓扑和命令供对应节点实施后使用，Docker 不是当前骨架启动的必需条件。

结束运行时在启动终端按 `Ctrl+C`，确认本次启动的应用退出。自动化检查需在清理步骤中停止自己启动的进程或容器；不得停止用户已有服务。示例使用 `--no-daemon` 避免留下持久 Gradle daemon。

## 实现与验证

- 优先复用 common 已有基础能力；保持 Domain 不依赖技术框架，事务只在 Application 写用例边界开启，其他分层约束见 [项目结构](项目结构.md)。
- Java 开发按需读取 [编码规范](../.github/skills/coding-standards/SKILL.md)：优先稳定 JDK 特性、不可变模型和构造器注入；Lombok 禁止 `@Data`，纯数据模型按白名单使用 `@Getter` / `@Builder`，Domain 优先显式构造及工厂。
- 仅运行能验证本次变更的检查。编译查看 error 和退出码；测试读取失败详情和通过/失败数量。现有仓库未提供测试源码，Gradle `build` 成功不能作为行为测试覆盖的证据。
- 文档变更核对本地链接、路径、命令前提、已实现与规划状态、UTF-8 编码、BOM、行尾和末尾换行，并执行 `git diff --check`；无需启动应用或构建。
- 仓库当前没有 `.editorconfig`；新增 Markdown 使用 UTF-8 无 BOM、LF 和末尾换行，已有文件保留一致的既有行尾。脚本格式遵循 [.gitattributes](../.gitattributes)。
- 交付前同步受影响文档，按 [AGENTS](../AGENTS.md) 更新 CONTEXT 和 SESSION-HANDOFF。报告实际检查结果、未解决项和下一节点，历史验收不改写为本次验收。
