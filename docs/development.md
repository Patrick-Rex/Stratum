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
| Spring Boot 插件 | 4.0.5 | 根构建；starter与gateway应用插件 |
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
| stratum-interface | ApiResponseFactory、HTTP 请求关联过滤器与分派注册 |
| stratum-application | Command / CommandHandler / UseCaseResult、工作单元事务代理、OperationLog 契约、CommandGateway；OutboxEventWriter 在已有工作单元内落盘领域事件；OutboxRelayApplicationService提供事务外有界批次入口 |
| stratum-query | QuerySpec、Query/Handler/Result/Context、QueryGateway 与 Spring 装配；统一路由及查询指标日志 |
| stratum-starter | 启动类、全局异常处理、国际化资源、Web 与 Actuator 依赖 |
| stratum-infrastructure | 已引入 Easy-Query 3.3.1（Boot 4 starter / MySQL 方言 / 处理器）、MariaDB JDBC、Flyway、Redis、Caffeine；已配置数据源与 Flyway；NODE-B03.2b 已验证真实 MariaDB 提交、回滚和嵌套事务 |
| stratum-gateway | 独立运行时依赖：Spring Cloud 2025.1.4 / Gateway Server WebFlux 5.0.4；不再由Starter聚合，已提供独立启动、固定上游路由、Header灰度、协议透传和代理超时/错误处理；已具备头体防护及管理直连CIDR限制，已绑定直连IP共享Redis限流，已提供JSON访问日志及受管理CIDR保护的Prometheus指标；双进程ERP闭环、权限组合及g3全工程回归已验收，D04.1完成 |
| stratum-job | Quartz/ShedLock Spring依赖、固定处理器注册表与有界虚拟线程执行器已接入；实际工作器持有数据库锁，已接入固定Quartz触发及数据库重试驱动，周期Relay继续E03后续节点 |

Domain 源码未导入框架，模块直接声明 common 依赖；根构建统一配置的 Lombok 编译期依赖同样作用于 Domain，不能将“源码无框架导入”表述为“所有配置均无第三方依赖”。

Easy-Query、MariaDB JDBC、Flyway、Redis、Caffeine 已接入依赖，已完成启动装配，事务行为验证见 NODE-B03.2b；本地JWT认证、会话撤销及管理员应用用例已实现；RabbitMQ已接入显式启用的连接配置，持久化Topic/Quorum拓扑及发布确认适配已建立，Relay、有序消费、死信归档及人工重放闭环已通过E02.5全工程验收；Quartz JDBC/ShedLock数据库锁已具备默认关闭的显式装配；任务有界执行器已接入，固定Quartz触发已接入，周期Relay、Nacos和完整观测组件仍待后续节点。选型约束见 [技术架构](技术架构.md) 和对应专题文档。

以下目标基线沿用原项目入口的既有约定，尚未接入时不作为当前依赖版本：

| 组件 | 目标版本 / 策略 |
|------|-----------------|
| Spring Boot | 4.0.5+（当前构建固定为 4.0.5） |
| MariaDB | 10.11+ |
| Redis | 开发单节点，生产 Sentinel |
| RabbitMQ | 4.3+；本机现有4.3.6，客户端Spring AMQP 4.0.2已接入，原生延迟重试拓扑已于E01.2验收 |
| Nacos | 3.2.0+ |
| 网关 | Spring Cloud Gateway Server WebFlux 5.0.4 / Cloud 2025.1.4，独立进程；项目外负载均衡 |
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

集成测试通过独立任务显式运行，不在普通 `test` 中隐式连接外部服务。先在本机 MariaDB 建立专用 `stratum_it` 数据库并授予测试账号权限，再设置 `STRATUM_IT_DB_URL`（`jdbc:mariadb://127.0.0.1:端口/stratum_it`）、`STRATUM_IT_DB_USERNAME`、`STRATUM_IT_DB_PASSWORD`，同时用 `STRATUM_IT_REDIS_PORT` 指定本机测试 Redis（认证时另设 `STRATUM_IT_REDIS_PASSWORD`）；全量Starter集成还需下述独立RabbitMQ测试空间配置，执行：

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

健康状态包含数据库与 Redis。本地 Caffeine 客户端默认最多 10000 条、写入后 30 秒失效，可用 `stratum.cache.local.maximum-size` 和 `stratum.cache.local.ttl` 调整；Cache Aside、空值保护、single-flight、分布式锁及补偿落盘已通过 NODE-B05 验收；补偿投递与执行仍待 Phase E。OpenAPI JSON（`/v3/api-docs`）与 Knife4j 页面（`/doc.html`）已接入并通过真实HTTP端点测试；业务响应文档已通过 C02.4b 的真实HTTP与浏览器验收。当前尚无 Prometheus 注册表或追踪后端，`/actuator/prometheus` 尚不可用。

当前没有 `docker/compose/`，本轮不实施部署编排。可以连接现有 Docker 服务，也可创建独立测试容器；不要复用其他项目的业务库或清空共享 Redis。集成测试使用独立数据库及带 `stratum` 前缀的键。

结束时在启动终端按 `Ctrl+C`，确认应用退出并清除当前终端密码变量。自动化验证在 finally 中关闭自己启动的进程或容器，保留用户已有服务。

## Outbox 写入约定

写用例通过 CommandGateway / UnitOfWork 开启事务，在保存聚合后调用 `OutboxEventWriter.append(event, "erp.material.created")`。写入器使用 MANDATORY 事务传播；事务外调用被拒绝，序列化失败或重复事件使事务回滚。事件信封包含 eventId、eventType、aggregateId、occurredAt、payload、traceId，UUID 归一化，JSON 数据上限 256 KiB，数据库时间按 UTC 保存。

Outbox已接通有界Relay、发布确认、有序消费、去重、死信归档与人工重放。自动调度仍待E03；写入成功、Broker确认和实际消费是不同证据，状态与运行边界见下文E02.5。

## 分布式锁使用

`DistributedLock` 契约位于 common，Redis 适配器由 Infrastructure 提供。`tryAcquire` 立即尝试，`acquire` 最长等待默认 5 秒；租约默认 30 秒，默认最多 256 个等待者和 2 个调度线程，重试间隔 50 毫秒。配置前缀为 `stratum.lock`；容量耗尽或竞争超时返回空结果并记录 traceId，Redis 故障直接传播。

锁键遵循 `lock:cache-rebuild:业务域:资源:id` 或 `lock:job:jobCode`，只能使用字母、数字、冒号、点、下划线及连字符，最长 256 字符。成功返回的 Lease 应使用 try-with-resources 释放；Lua 只删除匹配持有者 token 的锁，禁止调用方直接删除锁键。该短租约用于缓存重建；长任务续期、数据库写入并发与调度 ShedLock 各有独立职责。

锁已注册 `stratum.lock.acquisitions`、`wait`、`releases`、`hold` 指标（后三项同 `stratum.lock.` 前缀）。加锁次数按调用者请求计算，内部轮询不重复计数；等待与持有时长使用单调时间，持有时长每租约终结时只计一次且不超过租期。日志保留获取时的 traceId，释放线程无需继承上下文；`observability.lock.trace.enabled` 控制成功路径日志，竞争失败与异常始终输出警告。

## 缓存重建核心

`VersionedCache` 定义序列化版本值、读写/失效端口与按资源分级的 TTL 策略。空值 TTL 固定 60 秒，正常值按基础 TTL 加减抖动；暂时不可用与业务不存在是不同结果，不把超时转换为 Optional.empty。

Infrastructure 的 `SingleFlight` 合并同键请求，实际加载数量受容量限制，使用可关闭的虚拟线程执行器。调用方等待超时不会提前释放仍在运行的加载容量；回源函数必须有 I/O 超时或支持中断，并显式捕获所需权限参数，仅 traceId 由组件传递。RedisVersionedCacheStore 已接入版本比较、空值保护和失效屏障；VersionedCacheAdapterImpl 已装配完整读取门面。

业务缓存键最长 241 字符，预留重建锁前缀空间；格式与长度由 common 的 VersionedCache.validateKey 统一校验。Redis 的 `biz:`、`null:` 与 `version:` 三类关联键均有 TTL；版本屏障比最长值 TTL 多保留 60 秒。版本按固定宽度十进制比较，支持完整非负 long 范围。L1 每次命中前读取远端版本标记；同版本失效也会改变标记，因此其他实例能够识别变化。L1 寿命不超过 Redis 剩余 TTL 和本地配置 TTL；它减少数据载荷读取，但仍需访问 Redis，不提供断网旧值回退。

读取未命中后，门面使用本机 single-flight 与 Redis 重建锁，并在持锁后再次检查缓存；回源结果超出等待窗口或加载期间发生失效时，不回填旧结果。默认等待 5 秒、最多 256 个加载，可用 `stratum.cache.rebuild.wait-timeout`（最大 5 秒）和 `maximum-loads` 调整。正常值、已缓存空值、超时/容量不足异常分别处理；调用方应给数据库与网络回源设置不超过等待窗口的 I/O 超时。

缓存已注册 Micrometer 指标：`stratum.cache.access`（hit/miss）、`loads`、`load.duration`（value/empty/error）、`requests.failed` 与 `singleflight.merged/inflight`（其余指标同 `stratum.cache.` 前缀）。回源次数只计算实际加载，不重复计算合并等待者；指标不使用缓存键或 traceId 标签。`observability.cache.trace.enabled` 控制访问/回源日志，默认开启；日志包含键、结果、耗时和 traceId。当前仅支持启动时绑定，动态刷新与 Prometheus 导出属于后续节点。

## 提交后缓存与补偿

Application 的 CacheUpdateCoordinator 必须在已有工作单元中调用。它先写入 `stratum.cache.invalidate` Outbox 意图，再登记提交后版本更新或失效；事务回滚不会修改缓存。补偿载荷仅包含键、最低版本及 TTL 参数，不复制业务数据。所有缓存动作都提前登记意图，覆盖数据库提交后进程中断的窗口；即时更新成功也保留意图，由 Phase E 的幂等消费者处理。

更新失败会降级为带版本屏障的失效；Redis 完全不可用时记录 traceId、缓存键与事件 ID，保留已提交的业务结果与补偿意图。删除场景使用 invalidateAfterCommit，最低版本必须高于被删除对象的旧版本。补偿落盘、显式Relay与真实消费恢复均已验证；自动Relay调度仍待E03，启用消费不等于启用周期投递。

## 查询条件基架

QuerySpec.create 接收服务端字段白名单，将外部字段名转换成枚举，再由具体 QueryHandler 映射至 ORM 列。分页复用 BasePageRequest（默认 20，最大 200）；最多 3 个排序、20 个条件，文本参数最多 512 字符。支持精确、包含及同类型闭区间，未知字段、非法操作、类型不符或倒置区间均被拒绝。集合与参数类型保持不可变；包含查询的字面通配符转义和 SQL 参数绑定已由主数据真实库测试验证，Query / QueryHandler 已定义类型契约，QueryResult 复用 common 的 page、pageSize、total、items 并拒绝超量或不一致的结果；QueryContext 显式携带不可变身份与权限快照，必须由服务器构造，不能从 HTTP 请求体采信。QueryGateway 已按请求精确类型统一路由，启动时拒绝重复类型与 queryCode；执行时核对结果分页、显式传递并恢复 traceId，拒绝隐式加入活动事务。`stratum.query.duration` 按注册的 queryCode 与 success/error 记录耗时；`observability.query.slow-threshold-ms` 默认 300，慢查询及失败输出警告。当前已提供主数据HTTP查询，库存HTTP查询也已接通，订单HTTP查询已于C03.6f接通，身份认证已接入，业务RBAC与仓库数据权限仍待Phase D后续节点。

## 实现与验证

- 优先复用 common 已有基础能力；保持 Domain 不依赖技术框架，事务只在 Application 写用例边界开启，其他分层约束见 [项目结构](项目结构.md)。
- Java 开发按需读取 [编码规范](../.github/skills/coding-standards/SKILL.md)：优先稳定 JDK 特性、不可变模型和构造器注入；Lombok 禁止 `@Data`，纯数据模型按白名单使用 `@Getter` / `@Builder`，Domain 优先显式构造及工厂。
- 仅运行能验证本次变更的检查。编译查看 error 和退出码；测试读取失败详情和通过/失败数量。application 已提供命令路由及工作单元边界测试；其他模块没有测试时，Gradle `build` 成功仍不能作为行为覆盖证据。
- 文档变更核对本地链接、路径、命令前提、已实现与规划状态、UTF-8 编码、BOM、行尾和末尾换行，并执行 `git diff --check`；无需启动应用或构建。
- 仓库当前没有 `.editorconfig`；新增 Markdown 使用 UTF-8 无 BOM、LF 和末尾换行，已有文件保留一致的既有行尾。脚本格式遵循 [.gitattributes](../.gitattributes)。
- 交付前同步受影响文档，按 [AGENTS](../AGENTS.md) 更新 CONTEXT 和 SESSION-HANDOFF。报告实际检查结果、未解决项和下一节点，历史验收不改写为本次验收。

Lombok 升级到 1.18.44 以覆盖 JDK 25 兼容修复，依据 [官方变更记录](https://projectlombok.org/changelog)；项目沿用其 MIT 许可，不引入商业依赖。

NODE-B03.1 采用 Spring Boot 4.0.5 BOM 固定运行依赖，避免 ORM starter 引入不同 Boot 版本。Easy-Query 3.3.1 父 POM 为 Apache-2.0；Spring、Flyway Community、Caffeine 为 Apache-2.0，MariaDB Connector/J 为 LGPL-2.1。使用社区免费能力，不启用商业扩展。Boot BOM 当前管理 Flyway 11.14.1、MariaDB JDBC 3.5.7、Caffeine 3.2.3；以 Gradle 实际解析结果为准。

## HTTP 请求关联

RequestTraceFilter 为每个请求绑定 traceId，并写入 X-Trace-Id 响应头。传入值仅接受 1 至 64 个字母、数字、点、下划线或连字符，否则生成 UUID；统一响应体复用同一上下文。异步和错误再分派重用请求属性，分派结束或异常退出均恢复原线程上下文。过滤器不自动把 ThreadLocal 传入异步工作线程，执行器必须显式传播。完整 W3C/OTel 链路与请求日志仍待 F01；当前关联标识不代表已接入追踪后端。

## HTTP API 版本

ApiVersionConfiguration 使用项目 Spring MVC 7.0.6 的原生版本路由，读取 X-API-Version，缺省为1，显式允许版本1。沿用 Spring 语义版本解析，1、1.0、1.0.0 等价；未知版本、空头值或格式错误返回 HTTP 400 和错误码40001，details为空数组，支持已有中文/英文消息。业务处理器用 `@GetMapping(version = "1")` 等映射声明版本；未声明版本的 MVC 处理器也受版本校验。新增版本需更新允许列表和对应映射。参考 [Spring MVC 官方版本配置](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/api-version.html)。

## 统一业务响应

ApiResponseAdvice 仅应用于 interfaceadapter 与 query 包下的控制器，将 JSON 对象、普通字符串及空成功结果包装为 ApiResponse，复用请求 traceId；已有 ApiResponse 不再嵌套。保留 ResponseEntity 的状态和 Location，字符串按 UTF-8 JSON 重新计算长度。204/304 无响应体、二进制下载及非业务包控制器保持原协议，避免破坏 OpenAPI、静态资源和管理端点。业务错误必须抛出异常或显式返回 ApiResponse.failure，禁止使用裸对象构造错误响应；参数校验与协议异常映射已接入。

## 参数校验与错误响应

Interface 已引入 Boot validation starter，版本由根工程 Boot 4.0.5 BOM 管理，实际使用 Hibernate Validator 9.0.1.Final。控制器请求体使用 Jakarta Validation + @Valid，直接参数约束使用 Spring MVC 方法校验。字段校验明细最多20条，仅含安全字段名和固定提示，不回显拒绝值；非法JSON、缺失或类型错误参数返回40000；返回值校验失败仍为50000。业务异常保留显式错误码和安全明细，调用方不得在业务明细中放敏感数据。

GlobalExceptionHandler 保留 Spring ErrorResponse 的 HTTP 状态和协议头（如405的Allow），同时提供统一JSON错误结构；404映射40400，405/415等协议错误映射40000。未知内部异常返回50000，普通IllegalArgumentException按内部错误返回50000，不向客户端暴露异常消息；已知输入构造失败必须在边界显式转换40000；错误消息继续按Accept-Language选择，details始终为数组。

[Hibernate Validator 官方说明](https://hibernate.org/validator/)确认其采用 Apache-2.0 许可；9.0系列目前为有限维护，当前沿用项目BOM的兼容版本，后续随Boot基线维护评估升级。该校验器在应用内执行，无商业授权或外部调用费用，不引入Hibernate ORM。

MVC 测试探针控制器标记为 `@TestComponent`，测试配置使用 `@TestConfiguration`，仅通过测试显式注册/导入；避免被完整应用的组件扫描加载。单独的MVC测试不连接数据库，完整应用集成测试仍通过显式环境变量连接独立MariaDB/Redis。

## OpenAPI 与 Knife4j

根构建管理 springdoc-openapi 3.0.3 与 knife4j-openapi3-ui 4.5.0。Interface 使用 springdoc WebMVC UI starter 提供生成器及UI发现配置，Knife4j仅加载独立静态UI包，不引入其旧版Boot starter。JSON为OpenAPI 3.0，扫描interfaceadapter/query业务包；`/v3/api-docs/swagger-config` 指向本应用生成的 `/v3/api-docs`，页面入口为 `/doc.html`。已验证生成文档与对象、已有包装、字符串、空值、201创建结果和二进制下载的实际响应一致，浏览器可展示版本头、嵌套数据字段及统一响应示例。

[springdoc 3.0.3 官方发布说明](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.0.3)确认该版本使用Boot 4.0.5，许可为Apache-2.0。[Knife4j 4.5.0 发布说明](https://github.com/xiaoymin/knife4j/releases/tag/v4.5.0)与[该版本父POM](https://github.com/xiaoymin/knife4j/blob/v4.5.0/knife4j/pom.xml)可核对发布及Apache-2.0许可；仅使用本地静态展示资源，无订阅或外部调用费用。组件更新节奏独立于Boot，当前按固定版本验证兼容性。

OpenApiConfiguration 为自动包装的业务JSON结果补充ApiResponse模型、X-Trace-Id响应头及通用400/500错误描述，已有ApiResponse不重复包装。文档统一采用命名响应组件，保证Knife4j能够展开嵌套对象；静态资源和二进制返回保持原协议。Controller返回非默认成功状态时应同步声明 `@ResponseStatus` 或对应OpenAPI响应注解，避免动态ResponseEntity状态无法被静态文档推断。测试探针只在显式导入时装配，发布包中不包含这些接口。

## 写请求幂等

IdempotentApplicationService 在已有工作单元内执行写回调，并通过 IdempotencyRepository 把请求SHA-256摘要和业务结果JSON同事务落盘。调用方先做授权与输入规范化；作用域由服务端组合操作、API版本和主体，不能从请求体直接采信。客户端键限1至128个ASCII字母、数字、点、下划线、冒号或连字符，作用域最多200字符，保留大小写；请求和结果JSON分别限制为256 KiB。

同键同摘要重放已保存的业务结果，同键不同摘要返回40900。并发竞争由MariaDB复合主键判定，失败方的业务、Outbox和回执全部回滚；客户端沿用原键重试可重放胜出结果。写回调可能并发进入，因此只允许同事务数据库写入，外部消息仍须Outbox。每次HTTP响应的traceId由响应基架重新生成，不存入回执。请求DTO应稳定序列化，作用域内的结果DTO类型保持固定；当前不自动删除回执，不支持凭空重新使用历史键。

当前已提供持久化与Application入口，并已接入主数据创建/改名/删除命令；采购销售创建/履约命令也已接入，主数据与采购销售HTTP写接口已接通，主体认证仍待Phase D。验证包含真实MariaDB并发唯一提交、失败整体回滚、请求冲突与结果重放。

## ERP主数据写能力

NODE-C03.2 已交付 MasterData 聚合及 Create/Rename/DeleteMasterDataCommand，通过 CommandGateway 调用。四种类别为 MATERIAL、CUSTOMER、SUPPLIER、WAREHOUSE；物料使用编码、名称、基本单位，其他类别没有基本单位。同类编码唯一，编码与单位创建后固定，只有名称可修改。编码最长64字符、名称200字符、单位32字符，去首尾空白且拒绝控制字符；编码按精确大小写区分。

MariaDB的 erp_master_data 保存UUID、类别、编码、名称、单位和版本。唯一键保护同类编码，Easy-Query忽略固定字段的UPDATE，更新/删除核对乐观锁；业务表必须通过外键引用主数据，已有引用的删除返回40900。库存余额已通过带类别的外键引用物料与仓库，测试专用外键表继续覆盖通用引用保护；订单及订单行已引用交易方、仓库和物料。

创建、改名和删除均保存幂等回执，作用域绑定操作、v1、类别和服务器主体。改名/删除命令要求期望版本；不存在或跨类别ID返回40400，过期版本返回40900。主体字段是服务器内部输入，不代表已接入身份认证。业务与领域事件、缓存失效补偿意图同事务提交；主数据详情缓存键为 `biz:erp:master-data:{id}`，TTL为5分钟并加减30秒抖动。删除使用高于旧版本的屏障，防止旧缓存回填。

C03.2完成写能力；C03.5a已增加内部主数据查询，C03.6a/b已接通HTTP读写接口。采购销售已按确认范围实现分批履约，累计数量不得超过订单量；不包含审批、退货或反向冲销。

## ERP库存内部能力

NODE-C03.3 已完成Quantity、StockBalance、StockMovement与内部StockPostingApplicationService。数量无损对应DECIMAL(38,12)，最多26位整数及12位小数，超界或零变动拒绝；出库不得产生负库存。余额按物料/仓库唯一，固定维度不参与UPDATE，乐观锁防止并发覆盖；带类别外键保证引用真实物料与仓库并阻止删除。

库存流水只追加，保存订单/履约/订单行来源、方向、正数量、变更后余额、余额版本与UTC微秒时间。同一履约行和同一余额版本各只允许一条流水。C03.4h已通过复合外键将订单、履约及订单行绑定到同一履约明细，拒绝不存在或跨订单拼接的来源，并阻止删除被流水引用的来源。

StockPostingApplicationService.post必须加入调用方已有工作单元，同时保存余额、流水和erp.stock.received / erp.stock.issued事件。重复履约行、库存不足、并发冲突或事件写入失败均整体回滚；事务内不能吞掉这些失败后继续提交。此入口已由采购销售履约用例协调，未注册公共库存调整命令或HTTP端点；订单规则与幂等结果重放已完成，库存余额和流水已通过QueryGateway提供内部查询。库存当前不维护详情缓存。


## ERP采购销售写能力

NODE-C03.4已交付TradeOrder、OrderLine、Fulfillment及创建/履约命令。采购引用供应商，销售引用客户，仓库和明细创建后固定；单号为PO-/SO-加完整UUID，不保证连续。每订单和每次履约最多100行；数量使用DECIMAL(38,12)，人民币单价DECIMAL(19,6)，金额按各行精确乘积汇总后HALF_UP到分，最大36位整数。价格允许零，不含折扣、修改或取消。

分批履约要求当前订单版本，按剩余量推进PENDING、PARTIAL、COMPLETED；采购增加库存，销售扣减且不得负库存。同次履约按物料及行ID排序过账，支持同物料多行；订单、履约凭据、余额、来源流水、Outbox与幂等回执处于同一工作单元。失败全部回滚，原幂等键可重试；重复请求返回当时的订单结果快照。库存流水复合外键校验完整的履约来源，不存在的来源及跨单据组合不能写入。

创建事件为erp.purchase.created / erp.sale.created，履约事件为erp.purchase.fulfilled / erp.sale.fulfilled；库存事件继续独立落盘。事件可靠落盘不代表已发送MQ。内部命令、查询及业务HTTP已接通，权限控制仍待Phase D；无审批、退货、反向冲销或财务记账。


## ERP主数据读取

NODE-C03.5a提供MasterDataQuery及erp.master.list处理器，经QueryGateway读取四类主数据的独立ORM投影并返回不可变结果。必填类别始终作为条件；id支持UUID精确匹配，code/name支持精确及字面包含，三字段可排序，同值补ID升序；分页默认20、最大200。ID详情仍经同一路由和类别过滤，未找到返回空结果。接口与数据权限尚未接入，当前上下文只用于本地验证及关联日志。

Easy-Query的[官方条件文档](https://www.easy-query.com/easy-query-doc/ability/select/where.html)提供列引用及参数绑定表达式。真实MariaDB测试发现当前3.3.1的contains在仅含反斜线时未按字面匹配，因此本处理器使用固定LIKE模板、显式ESCAPE感叹号和绑定参数；百分号、下划线、感叹号及反斜线组合已验证，不拼接客户端SQL或属性路径。


## ERP库存余额读取

NODE-C03.5b提供StockBalanceQuery及erp.stock.balance.list处理器。id、materialId、warehouseId使用UUID精确条件，quantity支持BigDecimal精确匹配及闭区间；四字段可排序，同值补ID，分页沿用默认20、最大200。结果返回精确数量与余额版本，缺失余额返回空列表，不凭空创建零余额。QueryGateway拒绝活动写事务；当前仅内部读取，HTTP与数据权限按后续节点交付。


## ERP库存流水读取

NODE-C03.5c提供StockMovementQuery及erp.stock.movement.list处理器。id、balanceId、orderId、postingId、orderLineId支持UUID精确筛选；direction只接受RECEIPT/ISSUE；quantity和occurredAt分别接受BigDecimal及Instant的精确或闭区间条件。排序仅开放id、quantity、occurredAt，默认时间倒序，并补ID升序，结果保持分页上限。数据库DATETIME按UTC映射，返回当时余额、余额版本及履约来源，不用当前余额替换历史值。尚无HTTP业务接口和权限控制。


## ERP订单摘要读取

NODE-C03.5d提供TradeOrderQuery及erp.order.list。采购/销售类别为必填；id、orderNumber、partyId、warehouseId、status支持精确条件，金额与创建时间支持精确及闭区间。排序开放id、orderNumber、totalAmount、createdAt，默认创建时间倒序并补ID。摘要直接返回已保存金额、状态、版本及UTC时间，列表不读取订单行；完整明细已由C03.5e交付。HTTP与鉴权尚未接入。


## ERP订单详情读取

NODE-C03.5e提供TradeOrderDetailQuery及erp.order.detail，类别与UUID固定，结果为零或一个包含摘要和明细的不可变对象。采用Easy-Query单语句关联订单与行，避免分开查询在并发履约时混合头版本与行数量；按原始行号返回，最多100行。查询没有写事务，缺失或跨类别返回空结果，HTTP层已适配404。已验证100行明细、集合不可变及并发更新下的状态/版本/数量一致性；该详情不从写聚合仓储读取。


## 查询标量边界

C03.5g1为QuerySpec补齐数值及时间边界：BigDecimal展开后的整数与小数总位数最多512，通过precision/scale和long计算提前拒绝极端指数，不实际分配展开文本。日期限1000至9999年；Instant按UTC限制为DATETIME(6)可表示范围且仅接受微秒精度，拒绝纳秒而非截断。区间两端分别验证，业务数量与价格仍沿用自身更严格限制；具体依据见[查询规范](查询基架设计.md)。

## 履约历史与读写组合验收

FulfillmentQuery通过erp.fulfillment.list读取指定类别和订单的历史批次；按批次分页，默认订单版本倒序并补ID稳定排序，每批最多100行。支持履约ID、当时订单版本及UTC时间过滤。仅批量加载当前页明细，保留不可变数量和原订单行号；头和履约行不可修改，关联读取只使用订单行的固定列。缺失、跨类别或未履约订单返回空历史，HTTP层负责区分资源不存在。

C03.5g2通过真实采购收货与分批销售命令，验证主数据、订单摘要/详情、库存余额/流水及履约历史的一致结果；幂等重放返回当时快照而当前查询保留最新进度。外层失败后订单进度、库存、流水及履约均不暴露回滚数据，QueryGateway仍拒绝活动事务。此验收不代表业务HTTP、认证或消息投递已完成。

## 主数据HTTP写接口

C03.6a提供/api/erp/masters/{kind}的POST，以及其/{id}的PATCH和DELETE，kind为MATERIAL、CUSTOMER、SUPPLIER、WAREHOUSE。POST请求含code/name及物料baseUnit，响应201和Location；PATCH仅含name/version；DELETE通过version参数指定观察版本并返回204空响应。所有写请求要求Idempotency-Key，固定主体local-erp由服务器注入，Phase D接入认证前仅用于本地验证。

请求DTO限制文本、控制字符和版本；类别与基本单位关系仍由领域对象决定，应用层只捕获该输入构造的校验失败并转换40000，不包裹仓储或事件写入错误。控制器显式声明路径/参数名称，不依赖编译器参数名信息。重复创建/改名返回原快照，删除可重放；过期版本、重复编码、同键异参和已引用删除返回409，跨类别或不存在返回404。主数据GET已于C03.6b接通，库存HTTP查询已于C03.6c接通，订单HTTP已于C03.6d/e/f接通。

## 主数据HTTP查询

C03.6b通过Query模块暴露GET /api/erp/masters/{kind}及/{id}，复用已有主数据QueryGateway处理器；详情缺失或类别不符返回404。列表返回page/pageSize/total/items，默认1/20，HTTP拒绝小于1的页码、越界条数及非整数，最大200；未提供sort时沿用ID排序，多个sort参数分别使用field,asc或field,desc，最多3项。

列表仅接受id、code、name的精确匹配及codeContains/nameContains字面包含，条件按AND组合，未知参数、重复单值、非规范UUID及非法排序返回400。使用MultiValueMap保留原始重复sort，不拆分字段和方向的逗号。QueryHttpParameters只在输入构造回调中将格式错误转换400，QueryGateway在回调外执行；数据库与内部异常不会被其捕获。身份和权限来自服务器上下文，当前仍为本地匿名验证，未实现授权。

## 库存HTTP查询

C03.6c提供GET /api/erp/stock-balances和/stock-movements，统一经过QueryGateway。余额接受id/materialId/warehouseId精确UUID及quantity精确或quantityFrom/quantityTo闭区间；流水接受id/balanceId/orderId/postingId/orderLineId、direction（RECEIPT/ISSUE）、quantity及occurredAt，对数量/时间提供From/To闭区间。上下界须同时提供并各自验证，精确与区间条件按AND组合。

数值直接转换BigDecimal并受QuerySpec展开位数约束，不转换浮点数；时间解析为Instant后验证UTC范围与微秒精度。非法方向、纳秒、超界指数、倒置/单边区间、未知参数及非法排序返回400。余额允许ID、物料、仓库、数量排序；流水允许ID、数量、时间排序，默认最新优先。不存在的维度或来源返回空分页；接口不开放库存调整操作。已通过真实HTTP与MariaDB核对精确数量、来源、时间、错误响应及生成OpenAPI参数。

## 采购销售订单HTTP创建

C03.6d提供POST /api/erp/orders/{kind}，kind为PURCHASE或SALE；请求含partyId、warehouseId及1至100条lines，每行含materialId、quantity、unitPrice。输入数量须为正且在26位整数/12位小数内，单价非负且在13位整数/6位小数内，额外最大值约束保护极端指数。领域聚合保持自动单号、固定明细和汇总后HALF_UP到分的规则；超出总金额存储边界返回400。

写请求要求Idempotency-Key，服务端绑定local-erp，返回201、订单Location和快照；重复请求保留原订单及行标识，同键异参返回409，主数据不存在或类别不符返回404。应用层仅将纯领域构造中的明确输入校验转换400，主数据查询、仓储及Outbox故障不在捕获范围。已用真实HTTP验证两条1×0.005汇总为0.01、幂等与类别隔离、嵌套空行/超量/精度/极端指数及OpenAPI；履约HTTP已于C03.6e接通，订单读取HTTP已于C03.6f接通。

## 分批履约HTTP接口

C03.6e提供POST /api/erp/orders/{kind}/{id}/fulfillments；请求含显式version和1至100条lines，每行仅含orderLineId及正quantity，沿用12位小数/26位整数边界。返回201及postingId/order快照，Location为该订单履约历史的postingId过滤地址，读取端点已由C03.6f交付。服务器固定主体、必填幂等头、原请求重放与同键异参规则与创建一致。

重复行属于400输入错误，未知行/超剩余量/过期版本/库存不足为409，缺失订单或类别不符为404。旧幂等请求重放当时PARTIAL快照，不被后续COMPLETED状态覆盖。真实HTTP已验证采购和销售各两批完整推进且只各产生两批凭据/流水；库存不足不保存履约/回执，不推进订单，补货后同键可重试。输入校验及OpenAPI已同步；未加入审批、修改、取消、退货或财务记账。

## 订单与履约历史HTTP查询

C03.6f提供GET /api/erp/orders/{kind}的摘要分页，以及/{id}详情和/{id}/fulfillments历史分页。摘要接受id/orderNumber/partyId/warehouseId/status精确条件，以及totalAmount、createdAt的精确或From/To闭区间；默认创建时间倒序，允许ID/单号/金额/时间排序。详情返回order摘要与原行号顺序的lines，最多100条，单语句保证头和行快照一致。

历史确认父订单存在后，通过postingId、orderVersion及createdAt过滤；版本/时间支持成对From/To，默认当时订单版本倒序，允许ID/版本/时间排序。postingId映射内部QuerySpec的id字段，明确区分路径订单id并避免当前Springdoc对同名路径/查询参数的文档覆盖；履约POST的Location也使用postingId，已通过真实GET验证可访问。缺失或类别不符返回404，存在但无履约返回空分页，历史数量不跟随后续订单进度改变。

读端点仅调用QueryGateway。非法状态、Long溢出、非法金额/时间、倒置或不完整区间返回400，内部读取故障保留500；身份与数据权限仍待Phase D，未将本地验证视作鉴权完成。

## 库存余额上限冲突

C03.6g1区分单次输入非法与累计库存超过现有DECIMAL(38,12)存储范围：零数量仍由输入校验拒绝，合法正数量收货导致余额超界时返回40900。StockBalance仅在无损相加成功后替换数量；应用工作单元继续保证订单版本/履约/流水/事件/幂等回执全部回滚。真实HTTP已验证最大合法余额再增加最小单位0.000000000001时失败、原余额及版本不变。

高精度响应的验收使用BigDecimal解析JSON数字，避免测试客户端默认浮点树模型对38位数量舍入；服务端仍使用精确十进制读写，不调整业务数量或金额精度。

## ERP HTTP闭环验收

C03.6g2从真实HTTP创建物料、仓库、供应商和客户开始，完成主数据改名、采购2.5的分批收货、销售库存不足回滚、补货后沿用原幂等键出库及重复重放；最终库存0.5，订单/履约/流水查询相互一致。验收独立核对18条Outbox记录、10份成功回执及物料缓存版本1；不直接用仓储建立该用例业务夹具。另验证X-API-Version=2在写入前返回40001。

OpenAPI公共业务响应已增加40400和40900的标准错误模型、示例及trace头，与真实不存在/冲突行为一致。HTTP层父节点C03.6完成，C03.7继续全工程回归与阶段验收；认证、RBAC、数据范围、消息实际投递、后台任务及观测仍按后续节点执行。

## ERP阶段整体验收（NODE-C03.7）

2026-10-24 执行 `./gradlew.bat --no-daemon test :stratum-starter:integrationTest --rerun-tasks`，93项单元测试与175项真实MariaDB/Redis集成测试全部重新运行，零失败、错误或跳过。覆盖主数据、采购销售、分批履约、库存、幂等、Outbox落盘、缓存和HTTP文档；并检查Domain无框架导入、Application不依赖Infrastructure实现、Query无写操作及Infrastructure无HTTP端点。Phase C验收完成，认证及后续基础设施仍待实现。测试JVM已退出，本轮独立容器继续供下一节点使用。

## Token密码学基础（NODE-D01.1）

Infrastructure已接入Boot 4.0.5 BOM管理的Spring Security JOSE 7.0.4（传递Nimbus JOSE JWT 10.4），Domain只声明无框架TokenCodec端口。两库均为Apache 2.0开源许可，不引入商业服务或按量费用；版本与实现依据可查[Spring Security官方仓库](https://github.com/spring-projects/spring-security)、[JWT参考](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)和[Nimbus官方说明](https://connect2id.com/products/nimbus-jose-jwt)。

RsaTokenAdapterImpl使用调用方提供的2048至8192位RSA密钥，只签发/接受RS256；Access固定15分钟、Refresh固定14天，校验iss/aud、用途、必要claims、UUID标识、非负整数版本及精确有效期。令牌输入最大16KiB，错误统一UNAUTHORIZED且不保留解码异常原因或原文。7项真实RSA单元测试已通过，包括不同签名/算法、到期瞬间、用途混用、缺失/畸形字段和输入边界。

此阶段尚未装配密钥配置、Redis会话、HTTP登录或安全过滤链；密码学校验成功不能替代会话有效性、RBAC或数据权限检查。接口暂仍使用Phase C本地主体，后续D01.5替换。

## 外部JWT密钥装配（NODE-D01.2）

本地可用 `STRATUM_AUTH_JWT_ENABLED=true` 显式装配TokenCodec，并提供 `STRATUM_AUTH_PUBLIC_KEY`（X.509 PUBLIC KEY PEM）、`STRATUM_AUTH_PRIVATE_KEY`（PKCS#8 PRIVATE KEY PEM）、`STRATUM_AUTH_ISSUER` 和 `STRATUM_AUTH_AUDIENCE`。PEM使用真实换行，单块不超过16KiB；密钥2048至8192位，启动时以实际签名/验证检查配对。启用后缺失、非法、弱密钥或配对失败将阻止装配，错误和配置对象toString均隐藏敏感内容，不生成默认私钥。

当前组件默认未启用；这个开关仅装配密码学端口，不提供HTTP认证或授权，D01.5仍待接入过滤链。5项真实Spring上下文装配测试与7项RSA回归通过。测试密钥动态生成并只保留在测试进程内。

## Redis会话基础（NODE-D01.3a）

AuthSessions通过RedisAuthSessionsAdapterImpl管理 `auth:user:{userId}:ver`、`auth:sess:{sid}`、`auth:rt:{jti}`。用户版本首次在身份验证成功后初始化为0且不设TTL；建会话脚本先核对版本和标识唯一性，再原子写入会话及Refresh白名单，TTL跟随Refresh的14天到期时间。Access在密码学校验后还须逐请求检查会话所有者和用户版本，不缓存成功结果；版本用字符串比较，避免Lua双精度丢失long精度。

7项真实Redis集成测试通过，覆盖并发、TTL、版本精度、身份/用途、标识碰撞不产生部分状态；故障注入验证Redis不可用时拒绝请求且隐藏原因。脚本按既定单节点/Sentinel目标设计，不宣称支持Redis Cluster。当前未接入HTTP认证、Refresh旋转或撤销操作入口；测试中直接改变权威版本仅用于验证校验行为。

## Refresh原子旋转（NODE-D01.3b）

AuthSessions.rotate在一个Redis脚本中核对用户版本、会话所有者与旧Refresh白名单，登记同用户/会话/版本的新jti、更新会话到期时间并删除旧白名单。新Refresh仍为签发起14天，会话跟随当前Refresh到期；旧Access继续受会话和用户版本校验约束。旧Refresh只能成功消费一次，后继冲突或校验失败不消费旧令牌；调用方只能在确认脚本成功后返回新令牌。网络结果不确定时拒绝放行，不把重试当作再次签发成功。

本节点重新执行12项认证单元测试、13项Redis会话集成测试和2项ERP HTTP闭环回归，全部通过。覆盖并发唯一后继、旧Refresh重放、用途/身份/版本绑定、过期及状态损坏、后继冲突和真实RSA/Redis组合。HTTP登录/认证、撤销操作入口与RBAC仍未交付。账号来源待确认，当前已停止本轮独立MariaDB/Redis容器与测试进程；用户原有Docker服务保持运行。下次本地验证可启动保留的两个独立容器，连接配置仍仅保存在本机临时目录。

## 本地账号模型（NODE-D01.4a）

LocalAccount以领域UUID标识账号，只接收BCrypt散列。用户名是1至64位ASCII字母、数字、点、下划线或连字符，区分大小写且不自动裁剪；这是登录标识的技术边界，不作为员工姓名。BCrypt格式接受2a/2b/2y、成本4至12，限制持久化凭据的验证成本；签发成本由后续适配器固定。初始化管理员标记仅用于初始化幂等，不赋予授权。账号默认toString只包含ID，禁止把散列getter用于HTTP响应。

4项领域测试通过，验证身份生成、精确用户名、仅接受散列、重建/版本及敏感值不输出。仓储和PasswordHasher当前仅为端口，数据库与BCrypt计算由后续节点交付。

## BCrypt密码适配（NODE-D01.4b）

Infrastructure显式声明BOM管理的spring-security-crypto 7.0.4，沿用Spring Security的Apache 2.0许可。BCryptPasswordAdapterImpl生成2b、成本10、随机盐散列；输入必须非空且非全空白，UTF-8最多72字节，错误代理字符或超长输入拒绝，不做裁剪。验证仅接受模型已支持的散列成本范围，非法输入返回不匹配。适配器不持有原始密码，临时UTF-8缓冲清零，调用方负责清零其char数组。

4项实际BCrypt测试通过，覆盖随机盐、正确/错误密码、ASCII/中文/补充字符的72字节边界、无截断、错误编码和散列成本上限。依赖行为参考[Spring BCrypt API](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/bcrypt/BCryptPasswordEncoder.html)；当前尚未提供HTTP登录。

## 本地账号存储（NODE-D01.4c）

迁移V202610240008建立auth_user，存储UUID、大小写精确用户名、BCrypt散列、启用状态、初始化槽及版本。用户名唯一；初始化槽仅允许NULL或1且唯一，因此普通账号可多个、初始管理员最多一个。数据库约束阻止非法用户名、非散列值和非法状态进入表。仓储目前只支持新建及写工作单元内重建，不能覆盖既有账号；登录读取待独立Query节点实现。账号ORM异常统一脱敏，唯一冲突返回409且不保留SQL原因链。

6项真实MariaDB测试通过，覆盖散列持久化、大小写与唯一性、管理员唯一、并发仅一次提交、回滚/事务外拒绝和数据库约束。独立容器重启时动态映射端口可能变化，连接前须检查docker实际端口并更新本机临时连接配置，不沿用旧端口。

## 内部凭据查询（NODE-D01.4d1）

AccountCredentialQuery以固定一页一条的QuerySpec精确绑定用户名，通过QueryGateway执行只读投影；账号快照包含身份、散列及启用状态，toString隐藏全部内容，不提供HTTP端点。查询记录仅沿用网关的低基数操作指标，不输出用户名、密码或散列。凭据端口的应用适配待D01.4d2。

3项真实MariaDB查询测试通过，覆盖大小写精确匹配、禁用状态、未知账号、非法输入和活动事务内拒绝。

## 凭据端口装配（NODE-D01.4d2）

QueryAccountCredentialsAdapterImpl实现Domain的AccountCredentials端口，应用只依赖端口即可经QueryGateway读取精确凭据；认证前使用无身份上下文传递服务器traceId。没有HTTP凭据端点，也不绕过网关事务检查。4项真实MariaDB查询组合测试重新通过，包含端口装配、未知账号、非法traceId及活动事务拒绝。

## 初始账号工作单元（NODE-D01.4e1）

BootstrapAccountApplicationService首次创建初始化账号；重复调用忽略新的凭据，保留原用户名、散列和启用状态。BCrypt计算在事务外，写入由Application工作单元完成；唯一冲突回滚后以新的工作单元读取并发胜者，普通账号同名冲突不会升级为管理员。入口禁止嵌套调用方事务，消费后擦除密码数组，启动调用方也须保证外层清理。初始化标记不替代RBAC授权。

5项真实MariaDB测试通过，覆盖首次创建、缺失/非法输入拒绝、重复不覆盖、普通账号占名、并发收敛及外层事务拒绝。环境变量启动装配待D01.4e2。

## 环境变量初始化（NODE-D01.4e2）

启用STRATUM_AUTH_JWT_ENABLED时，应用启动任务在迁移和组件装配后、就绪前运行初始账号创建。首次必须提供操作系统环境变量STRATUM_AUTH_ADMIN_USERNAME、STRATUM_AUTH_ADMIN_PASSWORD；没有默认值，也不从普通配置文件或命令行属性读取凭据。直接读取原始环境属性，保留密码中的占位符、空格与特殊字符。初始化完成后可移除这两个环境变量；后续启动不会重置既有账号或禁用状态。失败阻止启动，异常原因链脱敏；这仍不代表HTTP过滤链已接入，后续D01.5交付。

本轮启动日志测试发现Easy-Query默认SQL参数输出会记录密码散列。现已关闭全局print-sql，并在账号写仓储和内部凭据查询上逐操作显式关闭；即使开发环境重新开启全局SQL日志，认证读写也不输出登录标识与散列。参数依据[Easy-Query配置说明](https://www.easy-query.com/easy-query-doc/framework/config-option.html)，逐操作API已核对本地3.3.1依赖。

19项真实MariaDB集成测试重新通过：4项启动配置、5项初始化、6项仓储、4项凭据查询。覆盖缺失/非法环境值启动失败、字面密码、重启不重置、并发、全局SQL日志开启下的凭据保护；测试上下文均关闭。

## 本地身份验证（NODE-D01.4f）

LocalIdentityApplicationService只依赖凭据与密码端口；正确用户名/密码返回领域账号UUID。未知、非法、禁用和错误密码统一UNAUTHORIZED，无账号差异明细；未知账号使用启动时随机生成的占位散列执行BCrypt比较，避免直接跳过密码计算，不宣称所有输入严格等时。原始密码数组在成功和异常路径均清零。数据库故障保留内部错误并拒绝认证，查询继续经QueryGateway且拒绝活动事务。

本轮8项领域/BCrypt单元测试和23项真实MariaDB集成测试均重新执行通过，0失败/错误/跳过；涵盖账号存储、查询、初始化、环境启动和身份验证。D01.4完成；当前尚无HTTP登录/安全过滤链，D01.5继续接入，不能把内部身份验证视作完整认证或授权完成。

## 登录与会话应用编排（NODE-D01.5a）

AuthSessionApplicationService在本地身份成功后读取权威用户版本、生成独立sid并签发令牌，只有Redis原子登记完成后返回；刷新先验证REFRESH用途，再消费旧白名单并登记同会话后继。Access每次检查密码学声明及Redis权威会话/用户版本，不缓存认证结果。令牌对toString固定脱敏，Access有效秒数为900；HTTP响应缓存与协议保护在后续接口节点接入。

6项真实MariaDB/RSA/Redis组合测试通过，覆盖登录/刷新/Access用途隔离、错误与禁用账号不创建会话、版本或会话失效、6并发刷新仅一次成功、存储失败不返回候选令牌，以及显式启用时的Spring装配。此节点未提供HTTP入口或RBAC。

## 登录与刷新HTTP协议（NODE-D01.5b）

启用JWT组件后，POST /auth/login接受username和password，POST /auth/refresh接受refreshToken，均沿用X-API-Version=1、标准ApiResponse和X-Trace-Id。成功响应data包含accessToken、refreshToken和expiresIn=900，携带Cache-Control: no-store与Pragma: no-cache。请求不开放用户ID、会话ID或版本；密码/Refresh字段只写，输入对象toString固定脱敏。MVC限制用户名64字符、密码72个UTF-16字符及Refresh 16KiB；实际密码UTF-8最多72字节仍由密码端口校验。

4项真实MariaDB/RSA/Redis与MVC协议组合测试通过，覆盖登录、旋转重放、用途混用、版本/输入边界、无Session、缓存头、输入不回显及伪造subjectId无效。这是实际MVC协议测试，尚未接安全过滤链或真实网络HTTP认证回归；D01.5c至D01.5f继续交付。

## 可信HTTP身份契约（NODE-D01.5c1）

Interface显式接入BOM管理的spring-security-web/config 7.0.4，沿用Spring Security Apache 2.0许可；不引入默认账号或自动登录页。Application的CurrentIdentity端口返回不含令牌的账号/会话/版本快照，安全适配器只接受本项目专用VerifiedAccessAuthentication且状态已认证；用户名认证对象、匿名对象和失效对象均返回401。身份本身不表示已通过RBAC，当前权限集合为空。

4项单元测试通过，覆盖可信类型、错误类型拒绝、不可用setter提升认证、元数据边界及新线程默认隔离；依赖解析确认7.0.4。上下文使用依据[Spring Security认证架构](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html)。过滤器、链装配和ERP身份替换仍待后续节点，不能将本节点视为HTTP鉴权完成。

## Bearer验证与安全错误（NODE-D01.5c2）

BearerAuthenticationFilter只接受单个Authorization头及有界紧凑JWT，不从查询参数或Cookie读取令牌；完整Access密码学与Redis校验后建立专用认证对象。缺失头暂交安全链决定是否公开；重复/畸形/错误用途/未配置认证服务返回401，认证存储和内部故障返回脱敏500。过滤器在成功、拒绝及下游异常后清理身份，支持异步和错误再分派；仍需后续无状态链装配才能保护业务端点。

SecurityErrorWriter提供带traceId、国际化文案和no-store的401/403/500标准响应，401增加WWW-Authenticate: Bearer。5项真实RSA/Redis过滤器集成测试通过，覆盖头解析、可信主体、用途、版本失效、Redis错误类型与服务故障失败关闭、上下文清理和403语义。

## 无状态HTTP安全链（NODE-D01.5c3）

HttpSecurityConfiguration显式注册安全代理，执行顺序在trace过滤器之后；链内Bearer过滤器不重复注册。禁用表单登录、Basic、默认logout、请求缓存和基于Cookie的认证，使用STATELESS与NullSecurityContextRepository，不创建或读取HTTP Session身份。业务/api/**、/auth/**要求认证，其他未声明路径拒绝。仅POST登录/刷新、GET健康及既有OpenAPI/Knife4j静态读取显式公开；错误再分派可输出原HTTP错误。未配置JWT时业务入口仍为401，不会回退到本地主体或默认账号。无Cookie认证的JSON协议禁用CSRF；将来若引入Cookie认证必须重新设计该保护。配置依据[Spring Security会话持久化](https://docs.spring.io/spring-security/reference/7.0/servlet/authentication/persistence.html)。

8项测试通过：6项完整MVC安全链与真实DB/RSA/Redis组合、2项真实HTTP容器验证。覆盖401/403区别、无登录跳转、HTTP Session/查询参数/自报头不能认证、权威版本失效、无Cookie、健康和文档仍可读、JWT未配置时实际业务路径受保护。当前ERP命令仍保留历史local-erp主体、查询上下文仍待身份接入，旧ERP HTTP测试也待迁移认证夹具；这些是D01.5d/e的明确未完成项，不表示认证阶段整体验收完成。

## ERP写入使用登录主体（NODE-D01.5d1）

主数据创建/改名/删除、采购销售创建/履约控制器通过CurrentIdentity取得服务器已验证账号ID并传给命令，已移除local-erp常量。幂等作用域继续由应用服务按业务、操作与主体隔离，客户端请求体subjectId或X-User-Id不会替代认证结果；权限细化仍待D02/D03。

新增可复用真实HTTP认证测试基类，以进程内RSA和随机环境凭据启动，再通过生产/auth/login获取Access，按用例清理会话、按类清理初始化账号。1项实际HTTP多账号组合用例通过：同键同账号重放，另一账号同键独立创建，回执分别归属真实账号，自报身份无效。既有ERP HTTP用例迁移仍待D01.5e，不将本节点的单项验证冒充全部ERP回归。

## ERP查询可信身份（NODE-D01.5d2）

主数据、库存余额/流水、采购销售摘要/详情/履约历史的HTTP查询均通过CurrentIdentity构造QueryContext，传递服务器验证的账号ID与trace；不再回退匿名。上下文权限暂为空，RBAC与仓库范围过滤仍由D02/D03接入。内部凭据查询在登录前继续使用匿名上下文，且无HTTP暴露。

query模块21项单元测试重新通过，新增2项验证可信账号/trace不可变传递、缺失身份或trace时拒绝，其余19项查询契约/规范/网关回归通过。D01.5d完成，真实已认证ERP全套回归在D01.5e继续。

## 已认证ERP真实HTTP回归（NODE-D01.5e）

主数据、采购销售、库存查询及完整进销存闭环测试通过生产登录端点取得Access，再调用真实HTTP业务端点；幂等回执断言和清理使用真实账号ID。网络测试覆盖Refresh旋转、重放与用途混用拒绝，匿名/畸形Access拒绝，以及权威用户版本变化后的Access/Refresh失效；响应检查trace传播和无Session Cookie。权威版本由测试直接改变，撤销管理HTTP入口仍待D02。

D01.5e1完成23项测试，审查发现独立InventoryQueryIntegrationTest仍包含未迁移的HTTP用例，因此登记e2并补齐。e2重新执行上述测试及库存查询，共33项真实HTTP/MariaDB/Redis集成测试通过，0失败/错误/跳过，父节点D01.5e完成。OpenAPI安全声明仍待D01.5f。

## OpenAPI安全契约（NODE-D01.5f）

生成文档以HTTP Bearer/JWT安全方案描述/api/**及/auth/**，仅POST登录/刷新明确无需Access。统一业务错误模型补齐40100与40300，受保护接口的401声明WWW-Authenticate；Knife4j继续消费同一OpenAPI，不额外维护接口清单。文档测试探针移到受保护的/api路径并经真实登录调用，公开文档和静态资源仍以匿名HTTP验证。

实际安全链回归发现旧Content-Length清除会触发Security 7.0.4空值解析异常，包装201响应变为500。ApiResponseAdvice现通过Servlet长度API清除旧长度，保持状态、Location及完整JSON；未更换当前依赖基线。对应上游问题见[Spring Security #18970](https://github.com/spring-projects/spring-security/issues/18970)，并已核对本地Spring 7.0.6响应头适配器实现。

6项响应包装单元测试及10项真实HTTP文档/安全链/认证网络集成测试全部通过，0失败/错误/跳过。覆盖生成安全声明、公开认证入口、401/403标准模型、受保护业务匿名拒绝、未授权路径403、201响应包装以及Knife4j脚本加载。D01.5完成，D01.6继续全工程组合验收；RBAC、撤销管理入口和仓库过滤尚未交付。

## 认证基础整体验收（NODE-D01.6）

2026-10-24执行 `./gradlew.bat --no-daemon test :stratum-starter:integrationTest --rerun-tasks`，33个任务实际执行；119项单元与234项真实MariaDB/Redis/HTTP集成测试全部通过，0失败/错误/跳过。测试覆盖认证、环境初始化、Redis会话旋转、ERP身份隔离、完整进销存、OpenAPI及响应包装；分层静态检查未发现Domain框架导入、Application依赖Infrastructure实现、Query写操作或Infrastructure端点。

D01认证基础完成。D02继续实现注销/撤销管理和RBAC，D03接入仓库授权；当前不能将已登录视为已完成业务权限检查。测试上下文和JVM已退出，独立测试容器继续用于下一节点；不执行部署。

## 本人会话原子撤销（NODE-D02.1a）

AuthRevocations端口只接受服务器身份中的账号UUID、会话UUID和版本。Redis适配器原子核对权威版本与会话归属；单会话退出删除该会话，全量退出递增用户版本，不扫描键。旧Refresh白名单即使保留也无法绕过会话/版本校验，按原TTL回收。后续凭据登录可取得新版本。全量退出的整数递增由Redis执行，再读取十进制字符串返回，不经Lua浮点转换；Long溢出保持原状态并拒绝成功。

6项新增撤销测试和13项会话回归通过，0失败/错误/跳过。覆盖错误用户/会话/版本不改状态、单会话隔离、全量Access/Refresh失效、刷新竞争、最大整数精度/溢出、存储错误类型和连接故障脱敏。尚未暴露注销HTTP端点；D02.1b继续接入。

## 本人注销HTTP入口（NODE-D02.1b）

启用JWT后提供POST /auth/logout与POST /auth/logout-all；必须携带有效Access，API版本仍为1。前者注销当前会话，后者注销当前账号所有既有会话。接口不接收身份参数，应用从CurrentIdentity取账号、会话和版本后调用原子撤销；客户端请求体或X-User-Id不能改变目标。成功返回200标准ApiResponse（data为null、含traceId），携带no-store/no-cache；旧Access再请求为401，全部退出后可重新凭据登录。

3项真实HTTP注销和6项Redis撤销回归通过，0失败/错误/跳过。覆盖其他会话隔离、全量Access/Refresh失效、重新登录、伪造身份无效、匿名/Refresh拒绝、不支持版本不撤销和无Session Cookie。该节点未包含他人强制下线、改密、账号启停或安全事件持久化；权限管理及审计按后续节点交付。

## 注销组合验收（NODE-D02.1c）

OpenAPI真实HTTP测试已核对两个注销端点均要求Bearer、没有身份请求体、保留标准成功模型及401 challenge。2026-10-24重新运行全工程 `test :stratum-starter:integrationTest --rerun-tasks`，119项单元与243项集成测试通过，0失败/错误/跳过；包括完整认证、注销、文档与ERP回归，D02.1完成。

RBAC账号/角色管理的产品范围现已由用户确认（ADR-004）；规范已确定的API/FEAT权限契约及只读存储基础可独立推进。管理员初始授权、账号启停、角色管理端点按D02.2d细分计划实施。

## 类型化权限契约（NODE-D02.2a）

Permission是Domain值对象，类型严格为API或FEAT，编码采用1至96位小写ASCII分段标识（段间可用点、冒号、连字符），不裁剪、忽略大小写或支持通配符。相同编码的不同类型不能互相授予权限。AccountPermissions只读端口要求经QueryGateway按可信账号读取；快照防御性复制并限制最多1024项有效权限，超限拒绝而非截断，禁用账号的许可检查和类型编码输出均为空/拒绝。上限用于约束单次查询及内存，不表示可以绕过仓库范围。

4项领域测试通过，验证精确类型、前缀无隐式授权、禁用/无权限、不可变性及格式/数量边界。当前只有领域契约，未装配权限读取、管理员授权或管理HTTP接口，RBAC仍未完成。

## RBAC关系存储（NODE-D02.2b1）

V202610240009建立auth_role、auth_permission、auth_user_role和auth_role_permission。角色编码唯一；权限以(type, code)唯一；两类关系使用复合主键和外键，禁止重复/悬空引用及隐式级联删除。角色/权限UUID由调用方领域生成，编码和名称有存储长度边界，角色具有非负持久化版本。没有插入默认账号、角色或授权。

实际测试发现MariaDB普通字符串比较接受尾随空格类型API；保留已执行迁移并以V202610240010补充二进制类型比较及编码字符约束，拒绝空格、换行和与Domain不一致的标识。4项真实库测试通过，覆盖类型/编码唯一性、格式和长度、关系外键/删除限制及角色与绑定同事务回滚。当前仅迁移基础，ORM映射、权限读取和管理授权仍待后续节点。

## RBAC映射与权限读取（NODE-D02.2b2/c）

四张RBAC表已建立Easy-Query映射，两个关联表显式使用复合主键；真实库测试验证只删除目标关系及事务回滚。AccountPermissionQuery通过固定账号QuerySpec，在一条SQL中读取账号启用状态和去重权限，仅选择ID、状态、权限编码及类型，不加载用户名或密码散列。最多允许1024项权限，超界或ORM失败返回脱敏内部错误，不静默截断；无权限角色不占用权限数量。

QueryAccountPermissionsAdapterImpl将Domain的AccountPermissions端口装配到QueryGateway，由应用传入服务器已验证的账号UUID和traceId。每次读取权威关系，不缓存授权结果；快照不可变，后续角色变更不会改写已有快照。未知账号返回空，禁用账号许可检查拒绝；写事务内读取继续由网关拒绝。该内部端口没有HTTP入口，也尚未用于业务接口放行，D02.2d及D02.3继续实现管理和运行时授权。

2026-10-24重新执行全工程 `test :stratum-starter:integrationTest --rerun-tasks`，33个任务实际执行，123项单元测试与255项集成测试全部通过，0失败/错误/跳过。包含6项权限查询测试（实际数据库查询与装配、ORM故障注入）、关系映射/迁移及认证、注销、ERP闭环回归；D02.2c完成。账号/角色管理产品范围已确认，下一节点D02.2d1继续实现，不能将权限读取基础视为RBAC完成。本次两个独立测试容器已停止，用户既有容器保持运行。

## 角色模型与权限目录（NODE-D02.2d1）

AuthorizationRole保持UUID和编码固定，名称可修改，权限有界且不可变。普通角色创建为空权限，只能分配PermissionCatalog已登记标识；管理员保留编码administrator，其全部权限由目录同步，普通权限替换入口拒绝修改。重建读取不会自动补授权，必须由后续可信初始化显式同步。目录包含ERP主数据读/维护、采购销售读/创建/履约、库存读取、账号/角色管理API及对应FEAT显示标识；目录本身不授予账号权限，也尚未用于HTTP放行。

4项新增领域测试通过，覆盖普通角色默认拒绝、身份及名称边界、权限不可变/登记校验、管理员保护与版本单调性；整个domain模块47项测试通过，0失败/错误/跳过。存储、管理员绑定和管理接口继续由后续节点交付。

## 管理协调与角色仓储（NODE-D02.2d2a/b）

V202610240011建立auth_administration_guard固定协调行，初始绑定标记与管理变更共享事务锁；它不是业务实体ID。AdministrationPolicy集中验证当前可用管理员与最后一人保护。d2a的49项领域和2项真实库测试通过，包含NOWAIT排他锁冲突与回滚释放；成员关系管理实现仍待d2c。

AuthorizationRoleRepositoryImpl仅在Application写事务内工作，批量登记权限目录并保留已有ID，新条目UUID在Domain目录中生成。角色名称与权限关系同事务保存；固定编码不参与UPDATE，版本校验失败返回脱敏409。权限批量解析，不逐项查询；读聚合使用FOR UPDATE并限制1024项关系，未知角色返回空。普通角色的业务权限规则仍在Domain，仓储不暴露HTTP。

d2b本轮49项领域测试及9项真实库测试通过，0失败/错误/跳过，涵盖目录幂等、权限替换/清空、重复编码与旧版本、整体回滚、事务边界及旧快照拒绝。MariaDB 11.8启用快照隔离时，旧读视图后的锁定读取可返回1020；仓储将其映射409，调用方必须放弃整个工作单元后重新读取，不使用旧授权继续。新事务读取最新状态的测试已通过；未修改全局隔离设置。行为依据[MariaDB快照隔离说明](https://github.com/mariadb-corporation/mariadb-docs/blob/main/server/server-usage/storage-engines/innodb/innodb-system-variables.md)。

## 管理员状态与成员关系存储（NODE-D02.2d2c）

AuthorizationAdministrationRepositoryImpl每个入口先获取数据库协调行排他锁，锁随Application事务结束释放；不使用跨事务内存标志。管理员统计按启用账号与真实administrator角色关系执行当前读，不加载凭据；旧快照发生冲突时返回409，不能据旧状态继续。账号角色替换最多64项，整组删除/插入与初始化标记均受同一事务保护；标记只提供置为完成的生产入口。

12项真实库组合测试通过，0失败/错误/跳过，包括5项新增管理存储测试、5项角色仓储和2项协调行回归。两个事务并发移除两名管理员时，结合Domain规则只有一次成功，最后一名保留；此时仍是测试编排验证，生产管理应用及HTTP端点尚待后续节点。D02.2d2存储基础完成。

## 管理员授权初始化用例（NODE-D02.2d3a）

BootstrapAuthorizationApplicationService从无事务入口进入单个Application工作单元，先持管理协调锁，登记目录并创建/同步administrator角色。首次只允许已启用且带初始化标记的账号获得角色，成员关系与initialized标记同事务提交。后续调用只维护目录，不恢复后来移除的成员；没有任何可用管理员时拒绝成功。相同目录重复调用不改角色版本，权限显示或API检查仍经正式权限读取端口。

16项真实库组合测试通过，0失败/错误/跳过；包括6项初始化测试和10项管理/角色存储回归。覆盖首次全目录授权、普通账号无默认权限、转交管理员后重启不重新授权、目录补同步、禁用/非初始账号拒绝、标记写入后的故障整体回滚、无管理员失败关闭及3并发初始化收敛。此节点只提供应用用例，实际启动任务接入仍待D02.2d3b。

## 启动接入管理员授权（NODE-D02.2d3b）

BootstrapAccountConfiguration在同一启动任务内先调用账号初始化，再将返回UUID交给角色初始化；二者任一步失败均阻止就绪，错误保持脱敏。账号创建与角色绑定是两个独立工作单元，角色失败时已创建账号可留待重试，不能返回启动成功；角色关系与其初始化标记本身保持原子提交。外部凭据来源和不重置已有账号的规则不变。初始化成功后账号通过真实管理员角色取得完整已登记目录，业务方法鉴权仍待D02.3，不能据此宣称HTTP已实行RBAC。

17项真实库/Redis/HTTP组合测试通过，0失败/错误/跳过：5项实际启动配置、6项初始化用例、2项网络认证、1项ERP身份隔离和3项注销。新增验证实际启动后可查到全目录权限，以及禁用初始账号导致角色初始化失败并阻止启动。真实HTTP测试基类和启动测试只删除各自随机账号与关系，删除本测试初始账号后复位独立测试库标记以隔离下一次启动；生产没有复位入口。

## 管理员初始化组合验收（NODE-D02.2d3c）

2026-10-24重新执行全工程 `test :stratum-starter:integrationTest --rerun-tasks`，33个任务全部实际执行；129项单元测试与274项真实MariaDB/Redis/HTTP集成测试全部通过，0失败/错误/跳过。覆盖角色模型、目录、协调锁/最后管理员并发保护、关系持久化、初始化与实际启动、认证/注销、OpenAPI及ERP闭环。D02.2d3完成，下一D02.2d4按已登记细分实现账号启停与撤销。账号管理HTTP和运行时RBAC尚未完成，不将初始化授权等同于接口鉴权。

## 账号状态与目标撤销基础（NODE-D02.2d4a / d4b）

LocalAccount允许改变启用状态，仓储只更新enabled及乐观版本；用户名、密码散列和初始化槽不更新。按ID的写模型读取持行锁，旧快照冲突返回409并要求整体回滚。d4a的5项领域及15项真实库测试通过，包括固定字段保护、状态版本冲突、回滚、溢出和初始化回归。

AccountRevocations由可信管理应用调用，只需目标UUID；Redis原子递增永久用户版本，没有会话时从1开始，所有旧Access/Refresh因版本不匹配失效。脚本通过INCR后GET字符串返回精确长整数，拒绝负数、畸形值及溢出，底层错误不泄露连接信息。d4b的11项真实Redis测试通过，0失败/错误/跳过，包含5项目标撤销和6项本人注销回归。账号状态逐次检查和实际启停应用仍待d4c/d4d，管理HTTP未交付。

## 认证实时检查账号状态（NODE-D02.2d4c）

AuthSessionApplicationService对Access先校验JWT和Redis会话，再经AccountPermissions/QueryGateway检查同一账号当前启用状态；Refresh先验证JWT，再检查状态，最后执行原子旋转。缺失或禁用账号返回401，查询失败关闭；不缓存启用结论，不从请求体读取目标ID。错误JWT在数据库查询前拒绝，状态查询失败不消费原Refresh。此检查不替代后续业务API的RBAC与仓库范围验证。

19项应用会话/MVC协议/过滤器组合测试及2项真实网络HTTP测试通过，0失败/错误/跳过。新增覆盖禁用和删除账号使已有令牌被拒、状态读取故障、无效JWT查询顺序；过滤器专项用明确的启用状态快照隔离账号存储，应用和网络测试使用真实数据库状态。实际管理启停用例仍待d4d。

## 管理员账号启停用例（NODE-D02.2d4d）

AccountStatusApplicationService从CurrentIdentity取得操作者，进入Application工作单元后先持管理协调锁，按当前启用账号及真实管理员角色检查权限。目标不存在返回404，过期版本或停用最后管理员返回409；相同状态且版本匹配时无重复副作用。改变状态时先乐观写入账号，再在数据库提交前递增Redis权威版本，停用与重新启用均执行撤销。

11项真实MariaDB/Redis测试通过，0失败/错误/跳过，包含6项启停应用与5项管理存储回归。覆盖普通/停用管理员拒绝、身份来自服务器、最后管理员及两个管理员并发自停用保护、外层事务拒绝、Redis故障回滚，以及注入提交前回调失败后数据库回滚但Redis版本保留。跨存储操作不提供分布式回滚；失败可能使会话额外下线，不能恢复旧版本或返回成功。管理HTTP仍待D02.2d7。

## 账号启停组合验收（NODE-D02.2d4e）

2026-10-25重新执行全工程 `test :stratum-starter:integrationTest --rerun-tasks`，33个任务全部实际执行；130项单元测试和293项真实MariaDB/Redis/HTTP集成测试全部通过，0失败/错误/跳过。新增3项认证生命周期测试：停用后Access/Refresh/密码登录拒绝、重新启用只接受新会话；停用事务已撤销但尚未提交时登录所得令牌不能在重新启用后复活；Refresh已读到启用快照但停用先提交时，原子旋转仍被版本检查拒绝。

D02.2d4完成，包含账号状态、目标撤销、实时认证检查和受保护启停应用。管理HTTP、其余账号/角色用例和业务RBAC尚未完成；D02.2d5已按创建账号、角色配置、成员分配和组合验收细分。测试服务器和JVM已随测试退出，独立数据库/Redis继续供后续节点使用。

## 管理员创建普通账号（NODE-D02.2d5a）

AccountCreationApplicationService只从CurrentIdentity取得操作者，先在短工作单元内确认当前启用管理员，再于事务外计算BCrypt，最后持协调锁重新鉴权并创建普通账号。账号UUID由领域生成，固定初始化标记为false，不创建角色关系，不返回凭据；消费的密码数组在成功及异常路径清零，外层调用方仍负责自身副本和代理拦截失败的清理。

12项真实数据库/Redis组合测试通过，0失败/错误/跳过，包含6项创建账号和6项启停回归。覆盖创建后真实密码认证与无角色/权限、未认证及普通账号在散列前拒绝、重复用户名不覆盖凭据、密码编码上界、散列期间管理员关系撤销后拒写、提交前故障整体回滚以及并发同名仅一份提交。管理HTTP尚未接入，角色配置与成员分配按后续子节点推进。

## 管理员角色配置用例（NODE-D02.2d5b）

RoleManagementApplicationService从服务器身份取得操作者，在管理协调锁内验证当前启用管理员。创建普通角色默认空权限；配置入口按预期版本同时修改名称和整组权限，编码保持固定，只接受已登记目录项；保留administrator角色继续由初始化目录维护，不能经普通配置入口改写。角色权限提交后由AccountPermissions权威查询即时读取，不在JWT或授权缓存保存旧结论。

17项真实库组合测试通过，0失败/错误/跳过，包含6项角色管理、5项角色仓储和6项账号创建回归。覆盖空权限创建、重复/保留编码、精确API/FEAT替换与清空、当前操作者权限、非法配置、过期版本、提交前故障整体回滚和并发仅一份配置提交。实际业务方法RBAC仍待D02.3。

## 管理员账号角色分配（NODE-D02.2d5c）

AccountRoleApplicationService只接受服务器当前管理员身份，在协调锁内以账号版本保护整组角色替换，最多64项。成员关系与账号版本同事务保存；复用账号状态持久化入口仅递增版本，启用状态和固定属性保持不变。移除管理员角色前检查最后一名可用管理员；相同集合且版本匹配时不重复撤销。真正变更在数据库提交前递增目标账号Redis版本，撤销失败回滚关系与账号；提交失败可能额外下线，但不得恢复旧令牌。

18项真实数据库/Redis组合测试通过，0失败/错误/跳过，包含6项成员分配、6项启停和6项角色配置。覆盖角色替换/清空、权限实时查询、固定属性、旧版本、未知角色和数量上界、普通账号拒绝、自身管理员转交、最后管理员并发保护及跨存储故障。管理HTTP和业务RBAC仍待后续节点。

## 管理应用组合验收（NODE-D02.2d5d）

2026-10-25重新执行全工程 `test :stratum-starter:integrationTest --rerun-tasks`，33个任务全部实际执行，130项单元测试和312项真实数据库/Redis/HTTP集成测试通过，0失败/错误/跳过。新增生命周期测试串联管理员创建账号、创建/配置角色、分配/移除成员与启停；使用真实HTTP登录并确认旧Access/Refresh撤销、角色授权即时读取以及重新启用后旧令牌不复活。

D02.2d5管理应用用例完成。管理查询已在计划中细分为资格检查、账号、角色、关系和目录验收，下一D02.2d6a；管理HTTP、运行时RBAC、仓库范围及后续消息/任务/观测仍未完成，不将应用验收记为整个计划完成。

## 管理查询资格检查（NODE-D02.2d6a）

AdministratorQuery经QueryGateway以固定账号QuerySpec单条SQL核对当前启用状态与administrator成员关系，只返回布尔资格，不加载用户名或密码。AdministrationQueryAccess从服务器QueryContext取得操作者，执行该查询并委托AdministrationPolicy判断；不采信权限字符串、初始化槽或先前资格。处理器将复用此组件；ObjectProvider仅延迟解析网关，避免处理器注册期间的构造循环，不缓存查询结果。

11项真实库测试通过，0失败/错误/跳过，包括5项管理员资格与6项原权限读取回归。覆盖角色绑定/移除、启停即时读取、伪造权限与初始化标记拒绝、匿名/身份不一致/畸形ID、写事务拒绝和ORM故障脱敏。当前只提供管理查询资格基础，账号/角色读模型和HTTP端点继续按后续子节点实现。

## 管理账号只读查询（NODE-D02.2d6b）

AccountManagementQuery提供账号分页及ID精确详情，经管理资格检查后查询专用AccountManagementView；只映射ID、username、enabled、version，不映射密码散列或初始化槽。白名单支持用户名精确/字面包含、启用状态及版本闭区间；排序追加ID保证稳定，页大小沿用200上限，SQL参数日志关闭。未知ID返回空结果，HTTP状态映射仍待管理端点节点。

10项真实数据库/HTTP夹具测试通过，0失败/错误/跳过，包括5项账号查询和5项资格回归。验证稳定分页、状态/版本实时读取、无敏感字段输出、LIKE元字符转义、注入文本、大小写精确匹配、白名单/类型/区间边界、非管理员与禁用管理员拒绝、事务边界及存储故障脱敏。没有增加管理HTTP端点。

## 管理角色只读查询（NODE-D02.2d6c）

RoleManagementQuery通过既有RoleView提供ID、code、name、version分页及精确详情，不在列表展开成员或权限。管理员角色可以读取；所有调用先校验当前启用管理员资格。编码/名称可精确或字面包含过滤，版本支持闭区间，排序追加ID且页大小最多200，参数化并关闭SQL参数日志。

9项真实库测试通过，0失败/错误/跳过，包括4项角色查询与5项账号查询回归。覆盖同名角色稳定翻页、配置后名称/版本即时可见、保留角色、未知ID、LIKE元字符及注入文本、白名单边界、匿名/伪造权限/已移除管理员拒绝、写事务边界和存储错误脱敏。关系有界详情与目录继续在d6d/d6e实现。

## 管理关系有界详情（NODE-D02.2d6d）

AccountRolesQuery和RolePermissionsQuery分别返回目标版本及完整角色ID/精确权限集合；先检查当前启用管理员，再以单条SQL读取目标和关系，避免混合不同查询时刻的版本。账号最多64角色、角色最多1024权限，多读一项识别超界并拒绝，不能返回截断集合。未知目标返回空结果，已有目标的空关系返回一条空集合快照；集合防御性复制，不加载凭据。

14项真实库测试通过，0失败/错误/跳过，包括5项关系详情、4项角色查询与5项账号查询回归。验证同事务版本/关系变化、空集合与未知目标区分、历史快照不可变、两类精确上界及超界失败、匿名/普通账号拒绝、事务边界及ORM故障脱敏。目录查询和管理读侧整体验收继续由d6e完成。

## 权限目录与管理读侧组合验收（NODE-D02.2d6e）

PermissionCatalogQuery经QueryGateway提供后端已登记的可授予权限，code/type/name来自与角色配置相同的PermissionCatalog，避免数据库残留未知项成为可授予选项；它表示目录，不代表当前账号授权。支持精确API/FEAT、编码/名称字面过滤与有界稳定分页，每次仍查询当前管理员资格，不能因目录为静态注册项而绕过授权。

2026-10-25重新执行全工程 `test :stratum-starter:integrationTest --rerun-tasks`，33任务全部实际执行；130项单元和334项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过。新增3项目录测试验证分页完整、类型/名称与领域目录和初始化管理员授权一致、最大页码安全、字面过滤及授权/事务边界；包含全部管理应用、查询、认证和ERP回归。D02.2d6完成，管理HTTP按d7a至d7e接续，业务RBAC仍待D02.3。

## 账号管理HTTP写入口（NODE-D02.2d7a）

API版本1提供 `POST /api/auth/accounts`（username、只写password）、`PATCH /api/auth/accounts/{id}/status`（version、enabled）及 `PUT /api/auth/accounts/{id}/roles`（version、完整roleIds）。创建返回201、Location及id/version，状态与角色更新返回200及新版本；成功响应禁止缓存。身份完全来自已验证Access，不接收操作者或初始化字段；账号与角色管理查询HTTP仍待后续节点。

输入Bean Validation拒绝缺失、类型/格式、负版本、超过64角色或空元素。密码端口明确声明的非法输入由创建应用仅在散列调用处映射400，含UTF-8超过72字节和全空白，不吞掉其他存储异常；密码数组在应用和控制器finally清零，DTO序列化和日志描述不输出密码。已获权限检查、目标404、并发/最后管理员409及撤销逻辑复用已验收应用用例。

22项真实数据库/Redis/HTTP测试通过，0失败/错误/跳过，包括4项新HTTP与18项创建账号/角色分配/启停应用回归。覆盖创建无授权、重复用户名、角色提升/清空、启停后旧Access/Refresh拒绝、普通/匿名/Refresh身份与伪造主体拒绝、最后管理员、缺失目标/角色、旧版本、密码编码边界、请求版本及无Cookie/trace/缓存协议。

## 角色管理HTTP写入口（NODE-D02.2d7b）

API版本1提供 `POST /api/auth/roles`（code、name）和 `PUT /api/auth/roles/{id}`（version、name、完整permissions）；权限元素包含精确code与API/FEAT类型。创建返回201、Location及id/version，配置返回200和新版本，成功响应禁止缓存。普通角色默认空权限，固定code不参与配置，角色名称及权限同事务修改；角色关系查询和目录HTTP仍待后续节点。

嵌套Bean Validation检查名称、编码、版本、空元素和1024项集合边界；Application只将角色领域创建/变更声明的非法输入映射400，包含目录外权限和保留administrator角色，存储错误保持原有语义。角色权限提交后实时读取，不依赖重新签发JWT。HTTP夹具按本测试账号和角色精确清理，先删除自身账号成员关系再删除角色。

14项真实数据库/Redis/HTTP测试通过，0失败/错误/跳过，包括4项角色HTTP、6项角色应用与4项账号HTTP回归。覆盖空权限创建、重复编码、精确授权与清空、已登录账号权限即时改变、普通/匿名/Refresh与自报身份拒绝、目录类型不匹配、保留角色、旧版本/未知目标、嵌套输入边界和版本头；非法配置保持原名称和版本。

## 账号管理HTTP读入口（NODE-D02.2d7c）

API版本1提供 `GET /api/auth/accounts`、`GET /api/auth/accounts/{id}` 与 `GET /api/auth/accounts/{id}/roles`。列表参数为page/pageSize/sort、id、username/usernameContains、enabled、version及完整versionFrom/versionTo；enabled严格接受小写true或false，其他值和重复参数返回400。全部读请求经可信身份和管理资格查询，成功响应禁止缓存；详情缺失返回404，已有账号无角色返回空集合及观察版本。

12项真实数据库/Redis/HTTP测试通过，0失败/错误/跳过，包括3项账号读HTTP、5项账号查询和4项账号写HTTP回归。验证稳定翻页、布尔状态/版本区间、无凭据或初始化字段、完整角色关系、未知目标、匿名/Refresh/普通账号拒绝、管理员关系移除后即时403、参数白名单/重复/UUID/范围边界及注入文本。角色与目录读HTTP继续由d7d接入。

## 角色和权限目录HTTP读入口（NODE-D02.2d7d）

API版本1提供 `GET /api/auth/roles`、`GET /api/auth/roles/{id}`、`GET /api/auth/roles/{id}/permissions` 及 `GET /api/auth/permissions`。角色列表支持编码/名称精确或字面包含、ID、版本及闭区间和白名单分页排序；权限目录支持code/name过滤与严格API/FEAT类型。关系详情同时返回版本和完整权限，已存在空角色返回空集合，目标不存在返回404；成功响应禁止缓存。

9项真实数据库/Redis/HTTP测试通过，0失败/错误/跳过，包括3项角色/目录HTTP、3项账号读HTTP与3项目录查询回归。覆盖分页、配置后名称和授权即时读取、API/FEAT目录名称及数量一致、全部读路由的匿名/普通/已移除管理员拒绝、未知目标、非法/重复参数、区间/版本及注入文本。D02.2d7e继续核对OpenAPI与组合协议。

## 管理OpenAPI模型契约（NODE-D02.2d7e1）

真实文档验收发现嵌套Row模型和同名操作的响应组件分别发生覆盖。启用springdoc.use-fqn区分完整Java类型；响应包装组件改用HTTP方法、路由、状态及媒体类型的稳定标识，不依赖随后才消歧的operationId。JSON业务字段和端点保持原契约，文档组件名称随之改变，使用旧组件名称的文档客户端需重新生成。

9项真实HTTP文档测试通过，0失败/错误/跳过，包括3项管理与ERP精确模型检查、6项既有文档协议回归。覆盖账号、角色、关系、权限目录与主数据/订单/库存各自字段，必填版本、集合边界、密码只写，以及统一外壳、认证、版本错误与二进制响应。管理安全和缓存等组合协议继续由d7e2验收。

## 管理HTTP协议组合验收（NODE-D02.2d7e2）

管理OpenAPI的12个操作均声明Bearer、版本头与标准成功/错误外壳，包含PUT；成功响应补齐Cache-Control: no-store和Pragma: no-cache，创建201另声明Location相对资源地址，401声明WWW-Authenticate。版本1、1.0和1.0.0等价，生成文档可使用规范化的1.0.0表示默认值。

30项真实MariaDB/Redis/HTTP测试通过，0失败/错误/跳过：覆盖管理写入/读取、模型和协议文档、原文档、真实网络登录、注销及ERP主体隔离。实际HTTP验证401/403、版本/输入错误、创建位置、trace、无Cookie、缓存保护、旧令牌撤销与实时管理资格。D02.2d7e及D02.2d7完成；下一d8执行管理能力整体回归，业务方法RBAC仍待D02.3。

## 管理能力整体回归（NODE-D02.2d8）

2026-10-25执行全工程 `test :stratum-starter:integrationTest --rerun-tasks`，33任务全部实际执行；130项单元与352项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过。验证管理员初始化、账号/角色管理及其HTTP和查询、并发保护、跨存储故障、认证撤销、OpenAPI及原ERP闭环。

D02.2d和D02.2完成；本节点完成依据为上述整体行为回归，不将文档维护计为实现。D02.3已细分为实时权限入口、本人功能权限、主数据、订单和库存方法授权；当前普通ERP接口尚未实施RBAC，仓库数据权限和后续消息/任务/观测仍待后续节点。

## 当前主体实时权限入口（NODE-D02.3a）

CurrentAuthorizationApplicationService仅从CurrentIdentity取得已验证账号，每次通过AccountPermissions/QueryGateway读取当前状态和有界不可变权限。requireApi只接受服务端PermissionCatalog的API条目，精确检查类型与编码；无API授权为403，身份缺失、账号停用/不存在或查询主体不一致为401。没有JWT权限声明、权限缓存或管理员绕过；查询失败关闭，写事务内读取继续由QueryGateway拒绝。

10项真实数据库/Redis/HTTP夹具组合测试通过，0失败/错误/跳过，包括4项入口测试与6项权限查询回归。验证同一身份授权/撤回即时可见、普通账号无权限、FEAT隔离、不可变快照、停用/未知/匿名账号、事务边界、错误主体、读取故障与trace传递。当前仅提供应用入口，/auth/me及ERP接口绑定按后续节点实施。

## 本人功能权限HTTP（NODE-D02.3b）

API版本1的 `GET /auth/me` 返回当前账号userId和按编码排序的featurePermissions，普通账号可得到空集合。功能编码仅用于展示，最多1024项且不包含API权限、角色、凭据、会话或内部版本；身份来自已验证Access，自报用户参数不参与判定。每次复用实时权限入口，返回Cache-Control: no-store与Pragma: no-cache，OpenAPI描述相同模型及Bearer要求。

13项真实数据库/Redis/HTTP测试通过，0失败/错误/跳过，包括3项本人端点、4项实时授权入口与6项文档回归。验证管理员全目录功能、普通账号空集合、同一Access看到功能增删、API/FEAT隔离、账号角色变更后的旧令牌撤销、伪造身份无效、匿名/Refresh/停用/注销401、重启用不复活旧令牌及版本错误。ERP业务API仍按下一节点绑定权限。

## 主数据API读写鉴权（NODE-D02.3c）

四类主数据列表/详情要求各自的erp.{material/customer/supplier/warehouse}.read，创建/改名/删除要求各自.write，读写权限互不隐含。类别映射由无框架MasterDataPermissions提供，HTTP方法在QueryGateway、CommandGateway及幂等回执读取前调用实时权限入口；FEAT和自报主体不能放行。普通账号默认403，匿名401；初始化管理员通过真实角色权限保持既有能力。

15项真实数据库/Redis/HTTP测试通过，0失败/错误/跳过，包含3项完整主数据权限矩阵、7项原主数据HTTP、1项双账号幂等身份隔离及4项管理/ERP文档。新增覆盖四类分别授权、读写隔离、同Access撤权、无权详情及跨类别写拒绝、创建与删除旧回执拒绝、拒绝无主数据/Outbox/回执副作用。原双账号测试为第二账号显式配置仅客户维护权限并清理自身关系；订单和库存API仍待后续绑定。

## 采购销售API实时鉴权（NODE-D02.3d）

采购使用erp.purchase.read/create/fulfill，销售使用erp.sales.read/create/fulfill；读取涵盖摘要、详情及履约历史。TradeOrderPermissions明确映射两类和三种动作，HTTP方法在业务读取、命令构造与幂等回执访问之前检查当前API授权。创建、读取、履约互不隐含，FEAT不放行业务；仓库范围仍待D03。

28项真实数据库/Redis/HTTP测试通过，0失败/错误/跳过，包括3项权限矩阵、11项原订单HTTP、10项查询及4项管理/ERP文档回归。验证普通/功能账号拒绝、采购销售隔离、三种动作隔离、相同Access看到授权撤回、创建及履约回执撤权后拒绝。单独履约授权完成采购收货及销售出库；拒绝和重复请求不增加流水、事件、回执或改变既有订单版本与余额。库存查询API继续由下一节点绑定。

## 库存API及运行时授权组合验收（NODE-D02.3e）

库存余额和流水HTTP统一要求erp.inventory.read，授权检查在参数解析与业务查询之前执行；库存FEAT、其他API或自报管理员头不能替代。D02.3完成：主数据、采购销售和库存全部生产ERP端点均已接入实时API鉴权；本人功能权限展示与业务放行分离，管理端点仍依据实际管理员角色，仓库范围待D03。

55项真实数据库/Redis/HTTP测试通过，0失败/错误/跳过；包含2项新库存授权、全部主数据/订单实时授权、本人功能、库存查询、ERP完整HTTP闭环、管理/ERP文档及授权存储/初始化回归。新测试用真实余额和来源流水验证授权后读取正确、同一Access撤权后403且不返回数据、功能权限与其他API不能替代、匿名/Refresh401。README已收敛为当前能力与清晰的历史验收口径；D02.4继续全工程验收，整个非部署计划未完成。

## 全工程RBAC及撤销验收（NODE-D02.4）

2026-10-25执行 `test :stratum-starter:integrationTest --rerun-tasks`，33个任务全部重新执行；130项单元测试和367项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过。覆盖全部ERP权限矩阵、账号角色管理及最后管理员保护、权威查询故障、会话撤销、密码/令牌边界、幂等重放、库存一致性、文档模型及业务闭环。

D02完成。下一D03.1先建立不绑定授权载体的仓库范围契约；仓库范围分配规则尚待确认后再落实存储及管理入口。D03尚未实现仓库过滤，不能将API授权成功等同于仓库授权完成；消息投递、后台任务、网关及完整观测继续按计划推进，部署暂缓。

## 仓库范围值对象（NODE-D03.1）

DataScopeType保留规范的七种范围类型；WarehouseScope只接受已确认的ALL/CUSTOM仓库语义。ALL不枚举仓库且不能夹带指定集合，CUSTOM按完整UUID匹配，空集合不授予访问。集合防御性复制，指定范围技术容量上限1024项，超界整体拒绝，不静默截断；未定义的组织/部门/本人范围和空目标均拒绝。值对象不决定账号或角色分配、默认范围或多范围合并规则。

5项新仓库范围与4项原权限值对象测试通过，合计9项，0失败/错误/跳过。覆盖精确集合、ALL、空集、类型矛盾、空UUID、不可变和容量边界。本节点不提供数据库或HTTP范围过滤，D03.2须在仓库分配规则确认后继续；本次独立测试MariaDB/Redis容器已停止，用户既有服务保持运行。

## 角色仓库配置领域（NODE-D03.2a）

RoleWarehouseScope以角色UUID标识独立配置，初始版本0：普通角色CUSTOM空集合，administrator固定ALL。普通配置支持整组替换，管理员配置不可经普通管理入口改写；恢复不符合管理员不变量的存储值直接拒绝。仓库配置版本只由保存成功后单调推进，不改变角色名称或API权限版本。RoleWarehouseScopeRepository限定当前读及写事务端口，数据库实现仍待b1/b2。

5项新角色范围、5项仓库范围与4项角色目录测试通过，合计14项，0失败/错误/跳过。验证默认空范围、管理员固定范围、ALL/指定/空集合切换、不可变快照、独立版本、非法身份与恢复状态。本次未启动服务，存储及HTTP过滤尚未实现。

## 角色仓库配置存储基础（NODE-D03.2b1）

V202610250001增加auth_role_warehouse_scope与auth_role_warehouse。配置与角色一对一、独立版本非负，类型仅ALL/CUSTOM；指定集合的复合外键仅允许CUSTOM，目标复合外键仅允许WAREHOUSE并保护引用删除；类型CHECK采用BINARY精确比较，拒绝尾随空格与大小写替换。角色清理级联移除自身从属配置及集合，不改变既有成员/权限关系约束。迁移为已有角色回填版本0，administrator为ALL、其他为空CUSTOM；生产角色仓储首次保存同步写入默认配置，后续名称/API权限更新不重置它。

16项真实MariaDB测试通过，0失败/错误/跳过，包括5项新迁移/约束、5项角色仓储和6项授权初始化回归。覆盖实际迁移回填语句、新角色默认、独立版本、非法类型/版本/类别、指定集合唯一性、ALL矛盾关系拒绝、仓库引用保护与角色/配置/关系整体回滚。重启测试容器后按实际端口更新本机临时连接状态；首次旧端口连接失败不计验收，以上为修正后运行结果。仓库配置写仓储仍待b2。

## 角色仓库配置写仓储（NODE-D03.2b2）

RoleWarehouseScopeRepositoryImpl在Application写工作单元及管理协调锁内使用当前读锁定角色和配置，指定关系最多读取1025项以识别超界。未知角色为空，已存在角色缺失配置失败关闭；固定编码必须与配置一致。保存按独立版本保护，先删除旧关系再切换类型和批量写入新关系，全部属于同一工作单元；不更改角色名称或API权限版本。引用/乐观/死锁及快照冲突脱敏为409，其他ORM错误为500。

18项真实MariaDB测试通过，0失败/错误/跳过，包括8项新配置仓储、5项迁移约束和5项原角色仓储。验证ALL/指定/空集合往返、旧版本和错误类别引用整体回滚、事务外拒绝、固定身份、缺失配置失败、1024完整集合及1025拒绝、同版本并发只提交一份、旧快照拒绝后新工作单元读取最新、存储错误脱敏和版本溢出保留原关系。只提供写侧存储，管理HTTP及业务范围过滤仍待后续节点。

## 角色仓库存储组合验收（NODE-D03.2b3）

2026-10-25执行全工程 `test :stratum-starter:integrationTest --rerun-tasks`，33任务全部重新执行；140项单元测试和380项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过。包括角色仓库领域、迁移/约束及并发仓储、全部账号角色管理、初始化、实时RBAC、会话撤销、OpenAPI与ERP闭环。

D03.2b完成，范围存储已具备，尚未接入订单/库存数据过滤或管理HTTP。D03.2c按目标访问谓词/查询、应用端口、管理员配置详情及组合验收细分。多角色并集通过数据库关系判断，不将每角色1024项限制错误地套到合并后集合，也不枚举或截断整个并集。

## 当前目标仓库访问查询（NODE-D03.2c1）

WarehouseAccessQuery经QueryGateway以固定userId/warehouseId条件返回单个布尔值，主体必须与服务器上下文精确一致；不提供HTTP接口。WarehouseScopePredicate以启用账号、当前角色、ALL配置或目标指定关联计算EXISTS，目标和主体均参数化，不读取凭据、不缓存或物化仓库并集。查询不确认目标业务资源存在性，不替代API授权。

13项真实MariaDB测试通过，0失败/错误/跳过，包括5项新访问查询与8项配置仓储回归。验证无角色/空范围拒绝、指定角色并集与重叠、ALL、配置及成员撤回实时可见、停用及未知账号拒绝、匿名/主体不一致/自报权限无效、活动事务拒绝、缺失配置失败关闭和ORM故障脱敏。两个各1024仓库的角色可访问完整2048并集，不受单配置上限截断；后续应用检查和业务数据过滤尚未接入。

## 当前仓库访问应用入口（NODE-D03.2c2）

AccountWarehouseAccess无框架端口由查询模块适配至WarehouseAccessQuery；CurrentWarehouseAuthorizationApplicationService从当前授权入口确认可信启用账号，再检查目标仓库。失效身份401、范围不允许403，读取故障保持失败；不接收外部操作者，不默认授予范围，不替代API鉴权或资源存在性检查，写事务内仍由QueryGateway拒绝。

12项真实MariaDB/Redis/HTTP夹具组合测试通过，0失败/错误/跳过，包括3项新应用、5项目标访问和4项原实时API授权。带初始化管理员的HTTP夹具补足多账号同时存在场景，发现目标查询嵌套SQL引用未带别名账号列的歧义，现将主体UUID直接参数绑定，防止内层解析扩大访问。修正后验证普通账号默认403、指定/ALL/撤回实时变化、其他管理员不影响当前范围、停用401、事务拒绝、故障顺序与trace传递。生产ERP目标检查仍待D03.4绑定。

## 管理员角色仓库配置查询（NODE-D03.2c3）

RoleWarehouseScopeQuery通过当前启用管理员资格检查后，以单条SQL读取角色固定身份、独立配置版本、scopeType及完整warehouseIds。专用只读视图不含凭据。未知角色为空结果，已存在空配置为一条空集合记录；已存在角色缺失配置、管理员不符合ALL不变量或集合超界均失败关闭。最多读取1025行识别超量，不返回截断集合。

14项真实MariaDB/Redis/HTTP夹具测试通过，0失败/错误/跳过，包括4项新配置详情、5项管理员资格和5项原管理关系回归。覆盖独立版本、ALL/指定/空快照、不可变、角色名称/API版本不影响配置、管理员ALL、1024完整/1025拒绝、匿名/普通/自报权限/已移除或停用管理员拒绝、事务边界与存储故障。角色配置HTTP尚待D03.2d。

## 仓库范围查询组合验收（NODE-D03.2c4）

63项真实MariaDB/Redis/HTTP夹具组合测试通过，0失败/错误/跳过；包含范围迁移/仓储/目标访问/当前应用/角色配置详情、管理员资格、账号角色及权限目录读取、原管理关系与账号/角色初始化。D03.2c完成，已验证当前身份、跨账号隔离、完整角色并集、当前管理员读资格、独立版本及故障关闭。

下一D03.2d已细分为管理应用和GET/PUT HTTP，接口使用独立配置观察版本。业务订单/库存仍未附加仓库过滤，当前查询基础不能视为D03完成；数据过滤与写用例目标检查继续按后续顺序推进。

## 当前管理员角色范围配置（NODE-D03.2d1）

RoleWarehouseManagementApplicationService仅从CurrentIdentity取得操作者，在独立Application工作单元持管理协调锁后核对当前启用管理员。按独立观察版本替换完整范围，管理员固定ALL配置拒绝普通管理改写；相同配置且版本匹配时不保存，旧版本仍409。范围结构校验限定ALL/CUSTOM与1024项，非法输入400；角色缺失404、引用/版本冲突409，其他存储错误500，不吞掉故障。

17项真实MariaDB/Redis/HTTP夹具测试通过，0失败/错误/跳过，包括6项新管理用例、8项仓储与3项当前范围应用回归。覆盖即时范围变化且现有会话保持有效、独立版本与no-op、当前管理员资格及固定ALL、非法输入与引用回滚、提交前失败整体回滚及同观察版本并发只提交一份。实际管理HTTP接入继续由d2实施。

## 角色仓库配置HTTP（NODE-D03.2d2）

API版本1的 `GET/PUT /api/auth/roles/{id}/warehouse-scope` 已接入。GET经QueryGateway返回id/version/scopeType/warehouseIds，PUT接受version/scopeType/warehouseIds并返回id/version；版本独立于角色API权限。成功响应no-store/no-cache，身份只来自有效Access。输入只接受精确ALL/CUSTOM、非负版本和最多1024个非空仓库UUID；ALL带指定集合400，未知角色404，旧版本或无效仓库引用409，administrator固定ALL不可改写。配置立即生效且保持已有会话有效。

23项真实MariaDB/Redis/HTTP测试通过，0失败/错误/跳过，包括4项新范围HTTP、6项管理应用、4项文档、4项原角色写HTTP、3项原角色读HTTP及2项网络认证。覆盖输入/身份/引用/版本边界、当前管理员资格撤回、完整配置和独立版本、无变化请求、同观察版本真实HTTP竞争及实时访问效果。生成文档核对14个管理操作的Bearer、缓存/trace/版本/错误外壳，范围模型精确ALL/CUSTOM与集合上界；D03.2d完成，下一e执行整体管理验收。

## 角色仓库范围管理整体验收（NODE-D03.2e）

2026-10-25执行 `test :stratum-starter:integrationTest --rerun-tasks`，33任务全部重新执行；140项单元测试和402项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过。覆盖角色范围领域/迁移/存储、跨账号与完整角色并集、可信当前应用、管理员配置HTTP与文档，以及全部认证、RBAC、管理能力和ERP业务回归。

D03.2完成。D03.3先迁移原匿名查询验收夹具至真实账号和显式ALL范围，再按库存余额、流水、订单摘要、详情、履约历史及整体回归细分；业务QueryHandler将强制可信主体并在计数/分页/记录读取前附加仓库谓词。当前生产ERP查询尚未附加该过滤，写入目标检查也待D03.4，不能将范围管理完成等同于整个数据权限完成。

## 业务查询可信夹具（NODE-D03.3a0）

库存查询复用真实HTTP登录管理员及其ALL范围；订单查询和履约验收使用独立普通账号、独立角色及通过写仓储显式配置的ALL范围。查询上下文绑定实际账号UUID，不自报API权限，夹具按引用顺序只清理自身授权数据；不增加生产匿名放行。

31项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过，覆盖原库存余额/流水、采购销售摘要/详情/历史和分批履约、并发与回滚断言。该节点只迁移验收身份，生产查询范围谓词从下一D03.3a1逐项接入。

## 库存余额仓库过滤（NODE-D03.3a1）

StockBalanceQueryHandler在数据库计数和分页之前附加当前启用账号的角色仓库EXISTS谓词，与所有客户端过滤按AND组合。外层余额表固定scope_balance别名，仓库列通过生成代理明确限定，主体UUID参数绑定，SQL参数日志关闭。共用主体校验要求规范UUID，匿名401、畸形403；无账号、停用、无配置或无范围返回空页，自报权限不能扩大范围。ORM错误脱敏为500且不回退至无范围读取。

32项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过，包括4项新余额范围、原库存查询/API、分批履约及目标权限查询回归；测试夹具最后收紧为固定非法UUID后，4项新范围用例再次通过。覆盖跨仓库总数/分页/目标过滤、多角色重叠与并集、同一会话ALL/指定/撤回即时变化、另一个管理员不扩大普通账号范围、未知/停用/缺配置、活动事务和ORM故障。库存API矩阵夹具显式配置ALL，生产默认仍为空；流水和订单范围过滤继续由后续节点接入。

## 库存流水仓库过滤（NODE-D03.3a2）

StockMovementQueryHandler以真实余额ID关联StockBalanceView，从带外层别名的余额列取得仓库，附加当前启用账号角色范围后再投影流水、计数和分页。客户端流水/余额/订单/批次/订单行过滤只会进一步收窄，不能指定归属或替代范围。沿用规范UUID主体校验，关闭SQL参数日志，ORM故障脱敏为500。

30项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过，包括3项新流水范围、4项余额范围及原库存查询/API和分批履约回归。覆盖真实两仓库三条收发流水、分批来源、总数和稳定分页、全部来源定位字段越权为空、同一Access看到ALL/撤回、多角色重叠与撤员、停用/缺配置及身份/事务/ORM故障。余额和流水共用独立身份/双仓库夹具，保留各自行为断言；订单过滤继续由D03.3b实施。

## 采购销售摘要仓库过滤（NODE-D03.3b1）

TradeOrderQueryHandler使用scope_order固定别名，在计数/分页前将订单类别、当前角色仓库EXISTS及客户端白名单条件相交。主体规范UUID参数绑定，SQL参数日志关闭，匿名401、畸形403，缺失/停用账号和空范围返回空页，ORM错误脱敏500。原订单API矩阵普通角色显式配置ALL，保持API维度的独立断言；生产默认无仓库不变。

38项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过，包括3项新摘要范围、原订单查询、API矩阵、HTTP和分批履约回归。覆盖采购销售双类型双仓库总数/稳定分页、ID/单号/仓库/交易方过滤相交、多角色重叠并集、即时配置和成员撤回、停用/缺配置及身份/事务/ORM故障。生产集合路径沿用/api/erp/orders/{kind}；详情和直接履约查询继续由b2/b3接入。

## 订单详情仓库过滤（NODE-D03.3b2）

TradeOrderDetailQueryHandler在单语句头行关联中附加订单仓库范围，固定scope_order_detail别名并绑定当前规范UUID主体；无权目标与缺失/类别不符均返回空结果，由现有HTTP入口映射404，不泄露头或明细。授权结果仍来自同一SQL快照，不另查头后拼接行；SQL参数日志关闭，ORM错误脱敏500。详情OpenAPI同步说明仓库要求及404语义。

45项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过，包括3项新详情范围、3项摘要范围、原订单查询/API/HTTP/履约及4项管理与ERP文档回归。覆盖两类订单默认空、指定/ALL/撤回、多角色并集和成员变化、停用、错误类别/未知目标及身份/事务/ORM故障；授权详情保留当前版本、状态、原行标识和精确履约量，原100行及并发快照测试继续通过。直接履约历史处理器的过滤继续由b3实施。

## 履约历史仓库过滤（NODE-D03.3b3）

FulfillmentQueryHandler先关联真实订单及类别，从明确外层订单列取得仓库并附加当前范围，再计数和分页不可变履约头。仅对已授权当前页批次一次性批量读取明细，保留200批×100行有界限制；两阶段SQL参数日志关闭，任何ORM故障均脱敏500，不返回部分结果。HTTP仍先确认范围内父订单，缺失、类别不符或无范围404；未履约的授权订单为空页。

48项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过，包括3项新历史范围、摘要/详情范围、原查询/API/HTTP/履约及文档回归。覆盖采购销售多批头分页及精确完整明细、跨订单/批次定位无效、多角色重叠并集、同一Access的ALL与撤回、成员/停用、身份/事务及头/行两阶段故障。原每批100行及组合协议继续通过；D03.3c执行全工程读取范围验收，写入目标范围仍待D03.4。

## 业务读取范围整体验收（NODE-D03.3c）

2026-10-25执行全工程 `test :stratum-starter:integrationTest --rerun-tasks`，33任务全部实际重新执行；140项单元和418项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过。D03.3完成，库存余额/流水、采购销售摘要/详情/履约历史的范围均在数据库中先于计数/分页及记录读取生效，覆盖当前账号、角色并集、实时变更、故障关闭和原ERP闭环。

下一D03.4已细分为创建绑定、原订单仓库访问应用/查询适配、履约绑定及组合验收。当前HTTP创建/履约仍只检查API权限，不能将读取范围完成等同于写入范围完成；写入检查将在命令及幂等重放之前执行。README已更新当前能力与本次验收口径，部署及后续消息/任务/网关/观测仍按计划推进。

## 订单创建目标仓库检查（NODE-D03.4a）

TradeOrderController.create在对应创建API鉴权后、命令构造和CommandGateway前调用CurrentWarehouseAuthorizationApplicationService检查请求目标仓库。范围不允许403，幂等回执不提供例外；仓库范围和API权限分别检查，有创建权限及范围即可创建，不要求读取API。创建OpenAPI同步说明双重授权和重放前检查。

23项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过，包括2项新创建范围、原订单API/HTTP、当前仓库应用与文档回归。覆盖采购销售默认空范围、指定/ALL、其他仓库拒绝、同键固定结果重放、撤回后原键拒绝及恢复后原结果可重放；拒绝不新增订单、Outbox或回执，ALL不能替代API，匿名/Refresh401。原订单HTTP及权限矩阵继续通过；履约目标范围待b1/b2接入。

## 当前原订单仓库访问入口（NODE-D03.4b1）

AccountOrderWarehouseAccess无框架端口经查询适配器调用已有TradeOrderQuery，固定类别/ID和当前账号上下文，只判断范围内目标存在，不新增无范围查询或HTTP读取。CurrentOrderWarehouseAuthorizationApplicationService先确认当前可信启用账号，再检查数据库原订单范围；缺失、类别不符或无范围目标404，失效身份401，故障保持失败。该入口不检查或授予API权限，调用方须单独检查履约API，并在写事务/幂等回执之前使用。

11项真实MariaDB/Redis/HTTP夹具集成测试通过，0失败/错误/跳过，包括3项新原订单入口、3项当前仓库入口、3项摘要范围和2项创建范围回归。验证双类型实际仓库、指定/ALL/即时撤回、移除全部读取API后范围检查仍独立有效、停用/未知/错误类别、事务拒绝、可信主体及trace传递、状态或目标查询故障关闭。生产履约绑定继续由b2实施。

## 收货出库原订单仓库检查（NODE-D03.4b2）

TradeOrderController.fulfill在履约API鉴权后、命令及幂等回执之前调用当前原订单仓库入口，从数据库原订单及当前角色范围判断访问。请求不能指定仓库归属；无范围、缺失或类别不符404，缺少API403。只有履约API及范围即可收货/出库，不要求读取API；范围撤回后原键也拒绝。OpenAPI同步授权顺序和目标语义。

36项相关真实MariaDB/Redis/HTTP集成测试及另行2项完整ERP HTTP闭环均通过，0失败/错误/跳过。包含2项新履约范围、创建范围、原API/HTTP/履约、原订单入口与文档回归。验证双仓库实际分批收货和出库、伪造归属参数无效、仅履约权限可过账、ALL与API独立、范围/API撤回后原回执拒绝、恢复后原结果重放及新批次继续履约；拒绝和重放不重复改变订单版本、凭据、库存版本/数量、流水、事件或回执。D03.4c继续组合验收。

## 写入范围组合验收（NODE-D03.4c）

141项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过，覆盖30个测试类中的创建/履约范围、全部订单与库存、幂等回执、当前授权入口、角色范围管理、余额/流水/历史过滤、原ERP闭环和文档。D03.4完成，写入口分别要求API及当前目标范围，拒绝在命令/写事务/回执之前生效。

新增2项组合测试验证重放目标替换：原仓库撤回后，改为允许仓库创建或改为允许仓库另一订单履约，并沿用旧键，均因请求指纹冲突409且data为空，不能读到原回执或增加业务效果；原目标仍按范围403/404拒绝。恢复ALL后原输入可重放原结果，订单、库存、凭据、流水、事件与回执保持原数量。README已更新写入能力；下一D03.5执行全工程数据权限验收，网关运行方式按后续技术确认处理。

## 全工程数据权限验收（NODE-D03.5）

2026-10-25执行 `test :stratum-starter:integrationTest --rerun-tasks`，33任务全部实际重新执行；140项单元和427项真实MariaDB/Redis/HTTP集成测试通过，0失败/错误/跳过。D03完成：角色范围管理、完整角色并集、订单与库存数据库过滤、创建/履约前检查、授权实时变更及重放目标替换均通过，同时覆盖认证撤销、RBAC、最后管理员保护、并发库存、幂等、Outbox落盘、缓存和ERP闭环。

用户已确认后续采用独立Spring Cloud Gateway进程，Starter继续承载ERP；先记录架构决定及细分实施计划，再接入网关依赖并本地联调。Gateway只处理入口治理，业务鉴权留在App；本次全工程验收未包含尚未实现的网关、消息投递、后台任务或完整观测。部署、Compose及外部负载均衡验收继续暂缓。

## Gateway依赖隔离（NODE-D04.1a1）

根工程仅为Gateway引入Spring Cloud 2025.1.4 BOM，Gateway使用官方Server WebFlux starter 5.0.4及Actuator，Starter移除Gateway运行时聚合。Boot继续由既有4.0.5强制平台约束，实际解析Framework/WebFlux 7.0.6、Reactor Netty 1.3.4和Cloud Commons 5.0.4；Starter运行时无Gateway/Cloud依赖。

依据官方[兼容表](https://spring.io/projects/spring-cloud/)，2025.1.x对应Boot 4.0.x；[2025.1.4清单](https://github.com/spring-cloud/spring-cloud-release/releases/tag/v2025.1.4)包含Gateway 5.0.4。官方[许可证](https://github.com/spring-cloud/spring-cloud-gateway/blob/main/LICENSE.txt)为Apache 2.0，持续发布维护，无库授权或按量费用；本地进程仍消耗正常运行资源。

Gateway build与Starter bootJar退出码0；2项运行时边界单元测试通过，验证WebFlux自动识别、Gateway/Netty类加载及无MVC/ERP/JDBC；另2项真实ERP HTTP闭环通过，均0失败/错误/跳过。生产Gateway尚无启动类，未把NO-SOURCE计为生产源码编译或网关路由验收；独立启动和健康检查由a2实现。已同步目标网关规范和当前模块能力。

## 独立Gateway启动（NODE-D04.1a2）

GatewayApplication只扫描gateway包，使用WebFlux/Netty启动；默认127.0.0.1:8081，通过STRATUM_GATEWAY_ADDRESS/STRATUM_GATEWAY_PORT调整。配置只暴露health且隐藏组件详情，启用graceful shutdown及10秒关闭阶段上界，不装配ERP或业务数据库。运行 `.\gradlew.bat --no-daemon :stratum-gateway:bootRun`，检查/actuator/health，Ctrl+C退出；Starter继续单独启动。

Gateway build及bootJar退出码0，4项测试通过，0失败/错误/跳过，包括2项原依赖边界与2项真实随机端口HTTP启动/健康/敏感端点隔离测试，类结束关闭Netty上下文及HTTP客户端。另用JDK25实际运行独立可执行JAR，直接HTTP探测200/UP后停止自身进程，确认已退出。本节点尚无上游路由，不能将启动成功视为入口治理完成；b继续实现路由与Header灰度。

## 固定上游及Header灰度（NODE-D04.1b）

GatewayRoutingProperties使用不可变启动配置，最多64个路径模式、每个最长512字符；按当前PathPatternParser校验语法。固定上游仅允许无凭据/查询/片段/前缀路径的HTTP(S)根地址，可选精确Host约束；不从请求参数或Header读取目标URI。路由默认关闭，STRATUM_GATEWAY_ENABLED=true后按STRATUM_GATEWAY_UPSTREAM转发，默认路径涵盖/api/**、/auth/**及现有API文档资源。

Header灰度用两条固定路由：启用STRATUM_GATEWAY_GRAY_ENABLED并提供STRATUM_GATEWAY_GRAY_UPSTREAM；默认X-Stratum-Release精确值gray，名称/值可通过STRATUM_GATEWAY_GRAY_HEADER/GRAY_VALUE配置。灰度优先，未精确匹配回到稳定路由；关闭灰度并重启实现回滚，无项目内节点选择或写入重试。STRATUM_GATEWAY_HOST可限定精确入口域名；完整属性前缀为stratum.gateway.routing。

Gateway build通过，8项测试通过，0失败/错误/跳过，包括4项新真实双上游/配置验收及原启动/依赖回归。验证原路径/查询/中文请求体转发、客户端伪造上游无效、未知路径不转发、Header值按字面量/大小写精确匹配及关闭灰度回退、未启用/域名不符不接触上游、非法URI与路径/容量/集合边界。所有测试创建的上游服务、HTTP客户端和网关上下文已关闭；超时/错误协议、请求防护及限流继续按后续节点实施。

## 跨入口关联标识策略（NODE-D04.1c1a）

Common的CorrelationIdPolicy共享X-Trace-Id/X-Request-Id名称、1至64个ASCII安全字符校验及缺失/非法时生成UUID的规则，无框架、线程或全局请求状态。Servlet RequestTraceFilter复用该策略，保持原HEADER常量、分派属性、线程绑定及finally恢复语义；不引入响应式ThreadLocal传播。

16项单元测试通过，0失败/错误/跳过，包括Common原3项及新策略3项、Interface的6项Servlet过滤器及4项当前身份适配回归。覆盖字符和长度边界、UUID替换、不裁剪/不改大小写、无线程状态副作用，以及既有异步/错误分派和线程恢复行为。协议节点已按共享策略、WebFlux透传、超时/错误细分；网关WebFlux接入继续由c1b实施。

## WebFlux关联与协议透传（NODE-D04.1c1b）

GatewayCorrelationFilter复用Common关联策略，在所有请求中补齐X-Trace-Id/X-Request-Id；唯一合法值保持原样，重复或非法值不采信，缺失请求ID沿用本次traceId。交换属性与Reactor上下文保存请求级traceId，响应提交前固定返回相同关联值，避免上游错误覆盖；不使用ThreadLocal绑定事件线程。

W3C traceparent按[Trace Context](https://www.w3.org/TR/trace-context/)校验格式、非零标识及版本长度，代理原样透传可识别前缀和未来版本扩展，总长度上限1024；非法/重复/缺失时移除traceparent及孤立tracestate，有效时不改写tracestate。这里只做代理透传与关联，未生成跨度或宣称OTel已接入。Bearer、X-API-Version与Idempotency-Key继续原样转发。

Gateway build和12项测试通过，0失败/错误/跳过，包括4项新增协议、原路由/启动/运行时回归。覆盖有效及未来版本W3C、业务协议头、缺失/超长/重复标识、非法W3C与孤立状态清理、错误上游关联响应覆盖、16个并发请求隔离和Reactor上下文/线程状态边界。真实本机HTTP夹具已提取共用，服务、上下文、客户端和执行器全部关闭；c2继续实现上游超时及安全统一错误。

## 显式关联失败工厂（NODE-D04.1c2a）

ApiResponse增加failure(errorCode,message,details,traceId)重载，显式校验安全关联标识并构造统一五字段失败外壳，不读取或建立线程上下文。失败data始终为空，details继续由原构造器防御性复制；空白文案回退默认。原成功及两类失败工厂保持原有线程上下文行为，不改变已上线响应契约。

19项单元测试通过，0失败/错误/跳过，包括Common9项（新增3项工厂契约）及Interface10项回归。验证错误码/文案/数据/明细/trace语义、不可变集合、缺省明细与文案、非法trace及空错误码拒绝、无线程状态副作用和原工厂兼容。c2已细分为显式工厂与代理超时/错误；Gateway错误处理继续由c2b接入。

## 网关代理超时与错误（NODE-D04.1c2b）

Gateway通过官方HttpClientFactory扩展替换有界连接提供者，保留TLS等官方装配。stratum.gateway.proxy默认connect-timeout=2s、response-timeout=5s、maximum-connections=128、maximum-pending=256、acquire-timeout=1s；对应环境变量STRATUM_GATEWAY_CONNECT_TIMEOUT、RESPONSE_TIMEOUT、MAXIMUM_CONNECTIONS、MAXIMUM_PENDING、ACQUIRE_TIMEOUT。连接和等待容量按固定上游分别生效，池随上下文关闭；禁用传输层额外重试与自动重定向，请求写入亦受response-timeout约束。响应超时是读取空闲上界，不是整个流的总时长限制。

网关自身连接/传输故障502、超时504、未知路由404，经显式traceId工厂返回统一五字段外壳，5xx沿用50000、data为空、details为空，移除残留实体/缓存/重定向及Cookie头，不回显异常、上游地址或请求参数。正常上游业务状态和正文原样透传；已经提交的流不能再改写为JSON，继续传播失败并由服务器结束连接。

Gateway build及17项测试通过，0失败/错误/跳过，包括5项新代理验收及原启动、路由、协议和边界回归。真实HTTP验证拒绝连接502、响应头前停滞504、POST连接中断不额外重试、未知路由及业务401/409原样；连接超时类型以模拟交换验证504，未冒称真实TCP黑洞验收。核对实际客户端超时/禁重试、有界池容量及关闭释放、配置拒绝和已提交响应保护。全部本机测试服务、客户端和网关上下文已关闭；D04.1c完成，下一d接入请求防护和管理来源控制。

## 网关请求头与体防护（NODE-D04.1d1）

GatewayRequestProtectionFilter在路由前校验头名称/控制字符、总字节及数量，并完整有界读取请求体；已知Content-Length先拒绝，未知长度分块由DataBufferUtils.join硬性限制。通过后保持原始字节并设置实际Content-Length，超限或未读完不会把部分请求体转发到ERP。客户端Forwarded、所有X-Forwarded-*及X-Real-IP被移除；server.forward-headers-strategy=none且关闭Gateway httpserver customizer，当前只承认直连来源，没有代理信任链。

stratum.gateway.protection默认maximum-body-bytes=1048576、maximum-header-bytes=16384、maximum-header-count=100、maximum-buffered-requests=32、body-timeout=5s，分别由STRATUM_GATEWAY_MAXIMUM_BODY_BYTES、MAXIMUM_HEADER_BYTES、MAXIMUM_HEADER_COUNT、MAXIMUM_BUFFERED_REQUESTS、BODY_TIMEOUT配置。体1KiB至8MiB、头1至64KiB、头数8至256、并发1至128、读取总等待50ms至30s；体上限×缓冲并发最大64MiB（聚合至数组复制时会短暂同时持有两份内容，不是整个进程堆上限）。缓冲额度一直保留至后续处理结束，取消、失败和成功都释放，不阻塞事件线程等待容量。

应用级体超限413、头容量431、缓冲耗尽503、体读取总时限408，沿用统一错误外壳。Netty硬解析头上限64KiB；在HTTP解析阶段已经拒绝的非法报文可能无法进入关联过滤器和JSON错误处理，不宣称所有底层协议错误都由应用包装。

Gateway build及22项测试通过，0失败/错误/跳过，包括5项新防护及全部原网关回归。真实HTTP验证固定/分块合法边界、两类超限不触达上游、头预算431及伪造代理来源不透传；池化片段校验聚合超限/取消refCnt归零，缓冲耗尽、读取超时后容量恢复，非法头/控制字符/字段数量和配置边界均拒绝。全部本机服务、上下文及客户端已关闭；管理来源路径与默认CIDR已由用户于2026-10-26确认，下一D04.1d2实施：账号/角色/权限管理及子路径，默认仅IPv4/IPv6回环，支持配置CIDR，只信任直连地址。

## 管理直连来源控制（NODE-D04.1d2）

GatewayManagementSourceFilter在请求体缓冲之前检查/api/auth/accounts、/api/auth/roles、/api/auth/permissions及其子路径。默认允许127.0.0.0/8与::1/128，stratum.gateway.management.allowed-cidrs或STRATUM_GATEWAY_MANAGEMENT_ALLOWED_CIDRS提供逗号分隔完整列表；增加网段时保留需要的默认项，空列表表示拒绝所有管理来源，最多64项，每项最长64字符。启动时以JDK25的InetAddress.ofLiteral解析IP字面量，不查DNS，拒绝主机名/区域标识/错误前缀。只检查同地址族前缀，允许网段内主机位；无法获得解析好的直连地址时拒绝。

路径按一次解码的段值及去掉矩阵参数后的值判断，折叠空段，精确管理根或其子路径才受控制；模糊编码分隔符、重复转义、点段400失败关闭，不改写实际上游URI。来源未允许403且在上游调用前终止；Forwarded/X-Forwarded-*/X-Real-IP均不能提供资格。允许来源只代表继续代理，ERP仍检查当前管理员和API/仓库权限。

Gateway build及26项测试通过，0失败/错误/跳过，包括4项新增管理检查及原网关全部回归。真实HTTP验证默认本机允许到达上游401（未授予业务身份）、指定CIDR排除本机时管理根/子路径/编码及矩阵形式403，伪造允许来源无效，非管理相似前缀和登录仍可到达上游。IPv4/IPv6前缀边界、缺失/未解析来源、拒绝全部、模糊路径、配置语法/容量/不可变性用模拟交换验证；没有声称真实IPv6监听验收。测试上下文、上游及客户端均关闭，D04.1d完成；下一e按用户已确认的直连IP共享令牌桶规则细分后实施。

## 共享Redis入口令牌桶（NODE-D04.1e1）

Gateway加入Boot统一管理的spring-boot-starter-data-redis-reactive，实际Spring Data Redis 4.0.4，未导入业务模块/JDBC。沿用[Spring Data Redis](https://github.com/spring-projects/spring-data-redis/blob/main/LICENSE.txt) Apache2.0免费许可及官方持续维护版本，无库授权或按量费用，本地Redis运行仍消耗资源。[官方脚本抽象](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)复用单一脚本及EVALSHA缓存。普通test关闭Redis健康探测以保持无外部服务回归，生产健康探测不关闭；真实测试显式执行 `:stratum-gateway:integrationTest` 并提供STRATUM_IT_REDIS_PORT，未提供环境直接失败、不跳过。

RedisGatewayRateLimiter按已解析IP字节的规范十六进制身份消费令牌，时间取Redis TIME；同命名空间固定四键（tokens/times/expires/policy）共用Hash Tag。两张Hash与有界ZSET记录客户端，默认4096、最多65536个活跃IP，每次至多清理128个过期槽位；满额拒绝新身份，不驱逐其他IP。补充速率默认20/s、容量40；闲置TTL取60秒与完整补充时间两倍的较大值，四键全部过期。相同命名空间的速率/容量/槽位策略不一致、任一共享键部分丢失或状态不一致均失败，不重置旧桶。

stratum.gateway.rate-limit配置replenish-rate、burst-capacity、maximum-clients、namespace、command-timeout；对应STRATUM_GATEWAY_REPLENISH_RATE、BURST_CAPACITY、MAXIMUM_CLIENTS、RATE_NAMESPACE、RATE_TIMEOUT。namespace最长40个小写字母数字连字符且以字母开头，必须为所有入口实例的同一受信值；默认stratum-gateway。命令默认500ms，允许50ms至2s；Lettuce每连接命令队列固定256，断连拒绝排队，保留官方已有连接选项。Redis连接由STRATUM_GATEWAY_REDIS_HOST/PORT/PASSWORD提供，默认本机6379和空认证；连接1s、客户端命令500ms，不把密码写入代码或日志。

Gateway build、26项原网关回归与6项显式集成测试通过，0失败/错误/跳过。5项真实Redis场景验证独立客户端并发共享、Redis时钟补充、IPv4/IPv6桶独立、策略漂移、槽位拒绝/惰性回收/TTL、错误类型/真实连接拒绝和四键逐个丢失失败；另1项校验配置、客户端队列选项、模拟损坏结果和未完成命令的超时。所有测试仅删除自身唯一命名空间四键并关闭客户端；独立Redis/MariaDB暂留供连续验证。当前仅交付共享存储能力，尚未给HTTP转发绑定限流；e2先接入标准错误，e3再绑定并验收真实HTTP效果。

## 入口限流错误协议（NODE-D04.1e2）

CommonErrorCode增加RATE_LIMITED（40002），Gateway自身HTTP429映射该码及安全文案“请求过于频繁，请稍后重试”；沿用ApiResponse五字段、空data/details、显式traceId与no-store，保留后续限流器给出的Retry-After秒值。HTTP503入口故障使用50000及“入口服务暂不可用”，不被内部连接异常改成上游502，也不回显存储地址/凭据/异常原因。正常上游业务错误继续原样透传。

Common9项与Gateway27项回归通过，0失败/错误/跳过，Gateway build退出码0。新增1项协议测试同时核对429/503状态、错误码/文案、五字段/空业务数据、关联、缓存、等待建议及嵌套内部异常脱敏；其余代理、来源、防护、路由及协议回归通过。本节点未给HTTP转发绑定限流；下一e3完成绑定及真实Redis/HTTP组合效果。

## 路由共享限流绑定（NODE-D04.1e3）

GatewayRateLimitFilter作为GlobalFilter在路由匹配之后、Netty转发之前消费真实直连IP令牌；同一IP不因稳定/灰度、请求路径、Bearer或客户端自报代理头而更换桶。正常放行继续既有协议代理，配额不足429/40002并返回Retry-After秒值，IP槽位耗尽503/50000并给出等待建议；Redis错误、超时、策略冲突或损坏结果均503且不调用上游。入口存储错误的映射仅作用于消费阶段，不把后续上游502/504改成503。无法取得已解析直连地址时400，不提供生产关闭限流的配置旁路。

健康和不存在路由没有进入GlobalFilter，不消耗桶；生产health保留Redis健康检测，Redis不可用时health可报告DOWN，不等同于配额耗尽。普通路由/协议验收通过测试初始化器注册显式Primary替身，保持独立于Redis；真实限流测试通过realRateGateway保留生产组件及独立存储配置，不使用该替身。

Gateway build、27项普通网关回归与10项显式集成测试通过，0失败/错误/跳过。包括6项原共享桶回归和4项新真实HTTP：两个独立网关上下文及稳定/灰度上游共用一IP额度，空桶429且POST未增加任何上游请求，伪造代理来源/灰度/幂等头不能换桶，健康/404不改桶状态；IP槽位耗尽、另一入口策略漂移和真实Redis连接拒绝均503且上游请求数不增加。拒绝响应核对五字段/空数据/trace/缓存/等待及故障脱敏；测试用空桶及未来时戳隔离HTTP耗时，没有依赖一秒内完成多请求的假设。各上下文、上游与客户端关闭，唯一测试四键删除；独立Redis/MariaDB继续供后续验证。D04.1e完成，下一f交付结构化访问日志和指标。

## 结构化入口访问日志（NODE-D04.1f1）

Gateway使用Boot StructuredLogFormatter扩展输出逐行JSON，公共字段timestamp/level/service/env/traceId/spanId/message/logger；service固定stratum-gateway，env取STRATUM_ENV或local。访问事件只登记upstream/status/latencyMs/traceId/clientIp，忽略未登记的键值及MDC；不从请求复制Authorization/Cookie/Set-Cookie、请求体、原路径、查询或幂等键。已有MDC的trace_id/span_id可作为格式缺省值，访问事件显式traceId优先；无OTel跨度时spanId为空，不生成虚假跨度或宣称全链路采集。

GatewayAccessLogFilter在来源/体防护之前登记响应提交回调，因此错误处理器设置最终状态后才记录一次事件。upstream只取stable/gray/local/unmatched/other固定别名，不记录上游URI；clientIp来自已解析直连地址。latencyMs使用单调时钟统计到响应提交（不是整个响应流结束），取消在尚未记录时用499日志标记；原子请求级标记避免提交/取消重复。普通业务代理保留既有响应及关联协议。

Gateway build及30项普通回归通过，0失败/错误/跳过。新增3项验证实际控制台7条访问JSON的200/204/403/404/429/502最终状态、稳定/灰度/健康归属、全部公共与访问字段、真实trace/IP以及敏感输入不出现；格式器转义引号/换行、忽略非白名单及MDC敏感键、保留已有跨度；取消后再提交只有一条499。测试上下文、HTTP上游、客户端与日志捕获器均关闭/恢复。10项Redis/HTTP显式集成测试沿用e3结果，本节点未重新执行；下一f2接入低基数Prometheus指标，并按用户已确认规则保护指标来源。

## 有界入口指标与受保护抓取（NODE-D04.1f2a）

Gateway接入Boot管理的micrometer-registry-prometheus 1.16.4，[Micrometer许可](https://github.com/micrometer-metrics/micrometer/blob/main/LICENSE)为Apache2.0，沿用官方维护组件，不收库授权/按量费用；只开放本地抓取能力，不启动外部Prometheus服务。/actuator/prometheus已暴露，并经既有管理来源过滤器限制完整路径、子路径及编码/矩阵形式；默认回环允许，其他来源需配置CIDR。health不受该来源限制，仍保留正常Redis健康检查。

GatewayMetricsFilter记录stratum.gateway.requests Timer，标签仅method/route/status：标准HTTP方法或OTHER、固定上游/本地/未知别名、最终提交状态或取消499。时长到响应提交，覆盖正常代理、限流、存储故障、来源/体拒绝及未知路由；同一请求取消与提交只记一次。按配置发布直方图与100/300/800ms、1/3/5s SLO桶，可从秒数count/sum/bucket观察请求、拒绝和故障，不记录IP、用户、原URL、trace或异常消息标签。

同时用ServerRequestObservationConvention及GatewayObservationConvention约束入站http.server.requests与出站http.client.requests（含active）。在创建Meter.Id之前归并扩展方法和动态URI，出站仅使用固定路由别名，不输出实际地址或请求URL；不靠事后MeterFilter改标签，避免注册缓存保留无界原ID。关闭重复的原生Gateway计时器，保留原生入站/出站观测与传播；不加入全局静态Registry，防止本地多上下文共享指标状态。

Gateway build、33项普通回归与10项真实Redis/HTTP显式集成测试通过，0失败/错误/跳过。新增3项验证真实Prometheus文本含请求直方图/JVM指标、204/429/503样本、6个自定义方法和6个动态未知路径均不进入任何入站/出站标签、指标来源403且健康仍200、取消后再提交只一条499；原W3C透传、范围、防护、错误与限流回归均通过。所有测试上下文、上游、客户端及Registry已关闭，独立Redis/MariaDB供连续验收。下一f2b补充真实共享桶429与抓取指标的联合断言及README启动说明后，才标记f2/f完成。

## 真实限流指标组合验收（NODE-D04.1f2b）

10项真实Redis/HTTP显式集成测试重新执行通过，0失败/错误/跳过。在两个独立网关及稳定/灰度共享桶场景中，新断言确认灰度POST的429只在实际拒绝的第二个网关形成一个本地Timer样本，另一个网关没有该拒绝样本；真实Prometheus抓取含对应route/status，不暴露请求trace或幂等键。抓取、health及404前后的桶时戳完全相同，原子额度不受观测请求影响。33项普通网关回归沿用f2a实际结果，本节点仅修改组合测试和文档，未重复运行普通回归。

README同步独立Redis连接、启用路由、健康/受保护指标抓取、完整管理CIDR列表、灰度回滚及共享策略，移除“尚无Prometheus入口”的旧说明。已实现能力表同步日志和指标，Starter的同等观测和完整OTel仍按F阶段推进。f2及f完成，Gateway本地入口治理还需g验证真实ERP双进程及全工程；部署、外部负载均衡和外部观测服务不计完成。

## Gateway可执行JAR与ERP闭环（NODE-D04.1g1）

Starter integrationTest仅在任务图依赖Gateway bootJar，并把实际归档路径作为测试系统属性传入；不把Gateway项目加入Starter依赖或扫描路径。GatewayProcessFixture用当前测试JDK25的ProcessBuilder参数列表启动该JAR，端口由Netty实际随机绑定并从有界输出识别；直连health就绪探测约30秒内完成，退出先等待10秒、必要时仅强制退出自身进程并再等5秒。使用稳定Process.waitFor(Duration)，不使用Thread.sleep。

子进程测试资源限制为256MiB堆、64MiB直接内存及2个可见CPU；日志每次读取最多1MiB，启动失败保留临时输出，成功关闭删除输出。排除继承的Stratum/Spring/OTel及JVM工具选项环境，避免开发者配置干预；Redis口令只通过子进程环境提供，命名空间随机，结束删除自身四键。没有给Gateway提供ERP/RSA/业务数据库凭据。

ErpWorkflowHttpIntegrationTest新增可覆盖的固定业务入口，保留原有全部业务断言；Gateway子类复用两项用例，经独立进程完成四主数据、精确金额、分批采购、库存不足回滚、补货重试、销售出库、幂等、查询与引用保护，及未知API版本写入前拒绝。断言18条同trace Outbox、10份幂等回执与缓存保留，核对实际Gateway JSON日志含同一trace，并验证Starter无法加载GatewayApplication。

4项真实MariaDB/Redis/HTTP测试通过，0失败/错误/跳过（原直连2项、实际独立JAR2项）。资源边界复核后再次重跑4项通过，确认自身Gateway子进程已退出；用户已有Java进程及共享Docker服务保留。仅验证Gateway为独立子进程、ERP运行在真实Starter测试上下文，不冒称两个生产部署实例。g1完成；完整认证/API/仓库实时撤回及限流无ERP副作用由g2验证，全工程验收由g3执行。

## 双进程认证与仓库权限组合（NODE-D04.1g2）

管理与订单写HTTP夹具提供可覆盖入口，订单范围夹具默认仍直连；Gateway派生用例仅选择独立JAR入口，复用原创建/收货/出库全部授权与数据库状态断言。身份、角色及双仓库夹具先准备，再启动网关；结束先关闭自身进程，数据库/事件/回执清理在finally执行。公共登录夹具提取签名验证及会话键登记，支持安全登记经Gateway登录/刷新获得的会话，结束按有限键集合清理。

9项真实MariaDB/Redis/HTTP测试通过，0失败/错误/跳过（原直连创建/履约各2项，独立网关创建3项/履约2项）。覆盖双类型创建和分批履约、API及仓库范围独立、指定/ALL/撤回、同一JWT立即生效、旧键不能绕过撤权、恢复原结果重放，以及匿名/Refresh不能访问业务。原订单/库存/凭据/流水/事件/回执快照在拒绝和重放时保持不变。

新联合用例实际经网关创建普通账号、登录、刷新、/auth/me及退出；伪造管理员Header/查询不能改主体，普通账号无默认API/FEAT权限，退出后Access401。已有普通读取账号的仓库列表按空→指定仓库2单→撤回变空变化，详情从200变404；随后给定创建API及ALL范围，再使本用例入口桶为空，POST返回429/40002，ERP订单、Outbox和幂等回执均未增加。补充读取范围断言后9项再次重跑通过；全部自身Gateway子进程已退出，用户服务保留。g2完成，下一g3全工程重跑并核对D04.1收敛，部署继续暂缓。

## 非部署网关全工程验收（NODE-D04.1g3）

2026-10-26设置独立MariaDB/Redis测试连接后执行：

```powershell
.\gradlew.bat --no-daemon test :stratum-gateway:integrationTest :stratum-starter:integrationTest --rerun-tasks
```

39个任务全部重新执行，构建成功。实际XML结果为179项单元测试（application6、common9、domain60、gateway33、infrastructure20、interface10、query21、starter20）、434项Starter集成测试及10项Gateway集成测试，共623项，0失败、错误或跳过。Job仍是骨架，NO-SOURCE不计为通过测试。此次结果覆盖此前节点的业务、鉴权、仓库范围、事务/缓存、独立网关与真实限流验收；早期各节点段落保留当时实现边界，不作为当前待办。

Domain框架导入、Query写SQL检查无命中；Starter测试只构建Gateway JAR，不引入其运行时依赖。独立Gateway子进程已退出，用户已有Java与Docker服务保留；本轮独立测试数据库/Redis暂留连续开发，结束前停止。D04.1及Phase D非部署范围完成，D04.2部署继续暂缓，后续进入Phase E消息与任务；消息、任务及完整观测尚未交付。

## 消息连接基础（NODE-E01.1）

Infrastructure引入Boot 4.0.5 BOM管理的spring-rabbit 4.0.2及RabbitMQ Java Client 5.27.1，不向Domain/Application加入客户端。使用官方维护的[Spring AMQP](https://github.com/spring-projects/spring-amqp/blob/main/LICENSE.txt)（Apache 2.0）及[Java Client](https://github.com/rabbitmq/rabbitmq-java-client/blob/main/LICENSE)可选Apache 2.0许可，无库授权或按量费用；沿用本地Broker，不引入收费服务。

连接配置由RabbitMessagingConfiguration显式装配，使用spring-rabbit库，不接入会自动创建默认连接的Boot AMQP自动配置模块。STRATUM_MQ_ENABLED缺省false，关闭时没有消息工厂、配置凭据对象或线程池，既有ERP健康检查不要求Broker。启用时必须提供STRATUM_MQ_VHOST、STRATUM_MQ_USERNAME、STRATUM_MQ_PASSWORD；无guest或默认口令。STRATUM_MQ_HOST缺省127.0.0.1、PORT缺省5672。配置toString隐藏全部内容，校验异常不回显原文。

STRATUM_MQ_TIMEOUT缺省2s，允许100ms至10s，分别应用于连接、握手、通道RPC、工作池等待及通道借用；关闭等待同值。CHANNELS缺省16，允许1至128；非零借用等待使缓存大小成为通道上限，同时设置Broker协商通道上限。WORKERS缺省4，允许1至16，工作池最多256项等待任务，退出等待最多5秒；最大接收消息体2MiB，心跳30秒。采用关联confirm和returns能力，关闭原生自动连接/拓扑恢复，后续由Spring消费者和Relay处理恢复。工厂延迟建立连接，本节点无RabbitAdmin、RabbitTemplate或监听容器，启用本身不声明队列、不发送消息，也不代表Broker健康验收。

本节点实际25项Infrastructure单元测试通过（新增5项），0失败/错误/跳过：真实Spring绑定、关闭隔离、凭据/UTF-8/资源边界、无启动TCP连接、握手无响应按时失败及线程池退出。另执行4项直连/独立Gateway ERP闭环与2项真实安全链/健康文档HTTP测试，均通过；Starter health实际返回UP，Gateway自身子进程已退出。后续E01.2在确认消费用途后建立真实Broker隔离拓扑，本节点不把连接配置测试计为消息收发完成。

## 首期Topic与原生重试拓扑（NODE-E01.2）

用户确认缓存失效补偿及ERP事件持久化留痕。开启消息后，RabbitTopologyConfiguration在所有单例装配后建立连接，先核实Broker版本至少4.3，再显式初始化RabbitAdmin；版本不可识别、过旧、连接或声明失败均中止启动。成功后管理员在重连时自动恢复声明，不静默忽略声明不等价。关闭消息时依然没有连接、管理员或拓扑资源。

持久化Topic stratum.topic分别以stratum.cache.invalidate、erp.#绑定q.stratum.invalidate与q.erp.events。每个业务队列有独立.dlq，死信交换机stratum.dlx为持久化Direct，使用原业务队列名为死信路由。四队列均为Quorum、不独占、不自动删除、无TTL；业务队列开启x-dead-letter-strategy=at-least-once，所有队列reject-publish，保留最早消息而非丢头。原生延迟保留在原队列，没有物理.retry队列。

stratum.messaging.topology配置：max-messages默认10000（1至100000）、max-bytes默认67108864（1KiB至256MiB）、delivery-limit默认5（1至100）、retry-min默认1s（100ms至1min）、retry-max默认30s（不低于min且不超过5min）。每队列使用相同阈值；Quorum允许少量在途超额，阈值不是严格资源硬上限。修改队列参数后必须考虑Broker等价声明与迁移，不直接删除现有队列以绕过冲突。nack返回不增加失败次数，reject失败才计数；本节点只验证Broker机制，有序消费进度仍待E02。

本轮25项Infrastructure单元及6项拓扑验收通过，0失败/错误/跳过。其中4项实际连接现有RabbitMQ 4.3.6的独立stratum-it虚拟主机：重复声明与双路由隔离、nack至少200ms原生延迟且失败计数不增、reject两次重投后进入正确DLQ并保留messageId/正文/持久属性、容量阈值最终否定确认且最早消息保留；另2项验证非法配置/关闭隔离及旧版/未知版本声明前失败。专用虚拟主机与同名专用账号必须由STRATUM_IT_MQ_VHOST/USERNAME/PASSWORD及PORT显式提供；测试只允许本机、严格随机名隔离空间，缺失直接失败。测试删除自身固定拓扑并关闭连接/线程，临时专用虚拟主机与账号保留连续验收，结束前删除；用户Broker持续运行。尚无生产发布器或消费者。

## Relay专用发布契约（NODE-E01.3a）

Domain增加OutboxPublisher端口，Delivery复用原OutboxMessage并携带正long持久化序号，不生成新的eventId或临时猜测顺序。CONFIRMED只表示Broker确认且可路由，不表示消费完成；RETURNED/REJECTED/TIMED_OUT/UNAVAILABLE都不能标记SENT。超时/不可用仍可能已到达Broker，重试必须沿用原eventId，由消费者数据库唯一去重守住副作用。

此端口仅供后续Relay在业务事务外使用，业务写用例继续只追加Outbox。Delivery摘要只含eventId和序号，避免日志复制正文。63项Domain测试通过，0失败/错误/跳过；新增3项验证空信封/非正序号拒绝、64位序号和原信封保留、摘要不泄露正文；源码无框架导入。当前尚无生产发布器、数据库序号分配或Relay，不把契约测试计为消息收发。

## 有界消息NIO传输（NODE-E01.3b）

RabbitMQ Java Client使用原生NIO；I/O循环独立于原回调工作池，单线程、最多4项等待任务，避免workers=1时I/O占满回调执行器。每连接写队列最多64项，写入队等待沿用STRATUM_MQ_TIMEOUT；请求帧上限128KiB，协商后遵循Broker限制。连接、握手及RPC边界保持原配置，NIO关闭动作使用已有有界回调池。两个执行器均由Spring管理，连接工厂先释放连接，再关闭线程池，最多各等待5秒。

本轮25项Infrastructure单元与6项拓扑验收重新执行通过，0失败/错误/跳过；实际NIO连接完成路由、nack延迟、reject超限DLQ及容量拒绝回归。配置/握手测试核对I/O与回调资源隔离、队列/帧/等待边界、无启动TCP连接、无响应握手按时失败、两池关闭终止。发送器与confirm判定仍待E01.3c，不把NIO入队成功视为Broker确认。

## Outbox有界发布确认（NODE-E01.3c）

RabbitOutboxPublisherConfiguration仅在消息开启时提供OutboxPublisher。它使用主工厂的独立发布连接，mandatory=true，关联confirm；正文是含eventId/eventType/aggregateId/occurredAt/payload/traceId/sequence的JSON，payload保持JSON值且小数按BigDecimal解析以保留数量/金额精度，消息持久化，messageId固定原eventId。头携带X-Trace-Id及stratum-sequence/aggregate-id/event-type，correlationId为业务trace；每次发送的客户端确认关联标识独立生成，重试不更换业务eventId。本节点保留既有业务关联值，尚无OTel跨度或持久化W3C上下文，不伪造traceparent。

只接受erp三段式事件或stratum.cache.invalidate，以及公共安全关联字符规则；非法JSON（含额外尾随值）、超512KiB信封或活动业务写事务中的发送在调用Broker前拒绝。stratum.messaging.publisher.max-in-flight默认16（1至128），容量耗尽立即UNAVAILABLE；序列化和等待同受该容量限制。confirm-timeout默认2s（50ms至10s），从send返回后开始；连接、NIO帧入队与通道RPC另按连接配置限时，不能把它描述为整次调用总时限。

CONFIRMED要求confirm为ack且没有mandatory返回；不可路由为RETURNED，Broker否定为REJECTED，确认等待超时为TIMED_OUT，连接/确认异常或中断为UNAVAILABLE。超时/异常/中断重置独立发布连接以清理未确认状态，保持中断标志；若重置失败，适配保持关闭直到重建，避免继续累积挂起消息。主声明/未来消费连接不因此重置。客户端随Spring销毁；生产业务当前未调用该端口，只有后续Relay可在事务外使用，不提供临时写事务直接发送或自动定时器。

本轮6项发布适配单元及4项真实Broker集成测试通过，0失败/错误/跳过。单元验证完整JSON/64位序号和高精度小数、mandatory优先/否定、确认超时、重置失败关闭、发送及确认异常、中断、并发容量恢复、事务/输入拒绝和资源销毁。真实验收覆盖两订阅持久消息/标识/序号/trace及36位高精度小数原值、主与发布连接物理隔离、不可路由与同事件恢复、容量否定确认、仅发布连接重置并恢复。不可路由探针先建立发布连接再删除队列，避开管理员首次连接的自动修复；真实故障以Broker返回判定。确认超时分支采用显式未完成确认替身，不冒称本轮真实Broker超时演练。拓扑6项及Infrastructure25项沿用E01.3b，本节点未重跑。

## 消息基础设施组合验收与操作（NODE-E01.4）

消息启用的完整Starter已在真实MariaDB/Redis/RabbitMQ下装配：唯一OutboxPublisher、健康HTTP为UP，当前无Rabbit监听容器/自动Relay；Outbox提交保留PENDING且队列为空，回滚不保留事件或消息，事务内调用发布器拒绝。此检查沿用现有DB/Redis健康项；RabbitMQ目前由启动版本/声明校验及发布结果检测，尚未额外注册持续健康项。两个独立Broker测试类和完整应用测试共享隔离配置及清理工具，避免不同守卫或测试先后依赖。

2026-10-26执行`test :stratum-gateway:integrationTest :stratum-starter:integrationTest --rerun-tasks`，39任务全部重新执行，构建成功。193项单元（application6、common9、domain63、gateway33、infrastructure25、interface10、query21、starter26）、446项Starter显式集成（含真实DB/Redis/HTTP、消息配置及真实Broker）和10项Gateway真实集成，共649项，0失败/错误/跳过。Job仍为NO-SOURCE，不计为测试通过。自身Gateway子进程、测试连接与消息拓扑均已退出/清理，用户服务保持运行。本轮独立测试容器和专用Broker空间暂供E02连续验证，最终结束前停止/删除。E01完成，严格顺序进度、Relay、消费者及死信重放继续E02，调度仍待E03；部署暂缓。

本地启用消息前，由Broker管理员预建专用虚拟主机和账号，授予该空间内拓扑声明/读取/写入权限。沿用已有数据库/Redis及认证配置，再设置：

```powershell
$env:STRATUM_MQ_ENABLED='true'
$env:STRATUM_MQ_HOST='127.0.0.1'
$env:STRATUM_MQ_PORT='5672'
$env:STRATUM_MQ_VHOST=Read-Host '已建立的专用虚拟主机'
$env:STRATUM_MQ_USERNAME=Read-Host '专用账号'
$env:STRATUM_MQ_PASSWORD=Read-Host '专用口令' -MaskInput
.\gradlew.bat --no-daemon :stratum-starter:bootRun
```

关闭开关并重启可恢复消息关闭模式，不删除既有Broker数据。消息开启后需要RabbitMQ 4.3+可达且队列参数等价；不自动删除冲突资源，不声明物理retry队列。应用退出用Ctrl+C，连接与有界线程由Spring关闭。此时启用的只有基础设施，生产业务仍只写Outbox，不代表已自动投递或完成消费补偿。

全量集成测试需要额外的独立RabbitMQ空间，虚拟主机及同名账号严格为`stratum-it-`加32位小写随机十六进制；不得指向业务空间。由管理员创建后，通过环境变量提供连接：

```powershell
$env:STRATUM_IT_MQ_PORT='5672'
# VHOST与USERNAME均为管理员已创建的同一随机测试名称。
$env:STRATUM_IT_MQ_VHOST=Read-Host '专用随机测试虚拟主机'
$env:STRATUM_IT_MQ_USERNAME=$env:STRATUM_IT_MQ_VHOST
$env:STRATUM_IT_MQ_PASSWORD=Read-Host '测试专用口令' -MaskInput
.\gradlew.bat --no-daemon test :stratum-gateway:integrationTest :stratum-starter:integrationTest --rerun-tasks
```

同时提供前述STRATUM_IT_DB_URL/USERNAME/PASSWORD和STRATUM_IT_REDIS_PORT；Gateway集成复用此显式Redis端口。缺少任一必要连接将失败，不静默跳过。测试删除专用空间内固定拓扑并关闭客户端；运行结束由创建者删除测试虚拟主机和账号、停止自己启动的独立测试容器，保留用户原有服务。Docker测试容器重启后应重新核实实际端口，不沿用可能已变化的随机映射。仅验证消息基础设施时，可给Starter integrationTest添加`--tests '*Rabbit*IntegrationTest' --tests '*MessagingApplicationIntegrationTest'`，仍需完整应用用例的DB/Redis配置。

## Outbox同事务顺序存储（NODE-E02.1a）

V202610260001新增outbox_aggregate_progress，精确区分大小写和尾随空格的aggregate_id为主键，保存已分配last_sequence及已消费consumed_sequence（初始0）。Outbox增加正event_sequence、聚合/序号唯一约束和进度外键，原UUID主键不变；聚合列改为utf8mb4_nopad_bin，避免不同标识被大小写或尾随空格规则折叠。消费进度目前仍为0，后续消费者成功后才推进。

OutboxRepositoryImpl在Application已有写事务内用参数化INSERT ON DUPLICATE KEY UPDATE锁定/初始化聚合进度，再在同一连接分配下一个long序号；锁保持到事务结束，随后仍由Easy-Query插入原信封。重复事件、非法JSON或外层失败必须向工作单元传播，使进度和事件一起回滚；无事务或只读标志拒写，Long.MAX_VALUE耗尽显式拒绝而不溢出。该技术存储不新增Application/Infrastructure事务边界，不向Domain加入JDBC。

这是数据库迁移兼容性变更：旧写入端不会提供新序号，升级前必须暂停旧写入。迁移在任何DDL前检查历史Outbox，非空时以SQLSTATE45000明确失败，需人工确认可证明的顺序迁移策略；不自动清空、排序时间或猜测UUID。当前本轮独立测试库迁移前实际0事件，迁移已成功执行；若真实环境已有事件，不能照搬此验收结论。预处理SIGNAL遵循[MariaDB 10.6.2+允许预处理语句规则](https://mariadb.com/docs/server/reference/sql-statements/prepared-statements/prepare-statement)，符合项目10.11+基线。

本轮12项真实MariaDB测试通过，0失败/错误/跳过：原Outbox5项及新序号7项。新增验证连续1/2、大小写/尾随空格隔离、原UUID/JSON高精度原文/trace保留、进度为0、失败与重复不留空洞、事务/只读拒写、耗尽、正序号/进度上界/FK约束，以及执行迁移原文的前置守卫拒绝非空且已有事件保持。此节点没有Relay、消费进度推进或并发顺序证明，后者由E02.1b继续验收。

## 聚合事件序号并发验收（NODE-E02.1b）

4项真实跨连接并发用例验证：新聚合后写者在首事务持锁期间不能提交，首事务提交后依次为1/2；首事务回滚后等待者从1开始；已存在进度且后写者先建立REPEATABLE-READ旧快照时，MariaDB1020作为40900冲突回滚，整工作单元显式重试后使用正确序号3；持有聚合A不会阻止聚合B提交。测试用实际CONNECTION_ID证明不同数据库连接，以5秒有界闩锁协调，不使用Thread.sleep；只提交有限的虚拟线程任务。

OutboxRepositoryImpl现在将序号分配JDBC与事件ORM写入的1020/SQLSTATE40001统一映射既有CONFLICT基线；1062重复事件继续映射40900。不在仓储内开启新事务或自动重试，调用方必须让失败退出工作单元后再重试，避免旧快照或部分提交。并发验收发现并修复了原JDBC快照错误会变成500的缺口。

本轮20项真实MariaDB/Redis/HTTP测试通过，0失败/错误/跳过：序号并发4、序号与迁移7、原Outbox5、直连ERP闭环2与独立Gateway ERP闭环2。原事件/回执/缓存与精确金额、分批收发货回归通过；自身Gateway子进程已退出。E02.1完成，仅证明持久化序号与事务行为，Relay和消费者仍待后续节点。

## Relay领取与完成契约（NODE-E02.2a）

Domain增加OutboxRelayRepository：claim只参与Application已有写工作单元，最多领取请求上限的不可变聚合头列表；finish在MQ事务外发布后，由新的工作单元按原竞争令牌和数据库租约核验结果。确认仅标SENT，不表示消费完成；租约过期、接管或状态已变返回false，不覆盖新状态。前序消费成功前不能领取后序，发送失败到达上限仍阻塞该聚合。

ClaimRequest限制1至128条、租期1秒至10分钟，采用毫秒精度；Claim保留原Delivery、UUID竞争令牌、数据库UTC截止时间和非负失败次数，摘要不包含正文或令牌，构造不依据本机时间推测有效期。RetryPolicy限制失败次数1至100、首次延迟100ms至1min、封顶不超过1h；指数退避封顶后立即结束计算，即使Integer.MAX_VALUE次数也不会无限循环或溢出。

本轮67项Domain测试通过，0失败/错误/跳过，新增4项验证领取边界、不可变证据及摘要、封顶退避和非法参数。Domain源码框架导入检查无命中。本节点没有数据库领取、MQ调用或调度，后续E02.2b实现持久化，不把契约测试计为真实租约互斥。

## 有序头事件与租约存储（NODE-E02.2b）

V202610260002为Outbox加入lease_token/lease_until及PROCESSING租约一致性约束、到期索引；所有旧已提交迁移保持原校验和。OutboxRelayRepositoryImpl只在Application已有写工作单元中执行，状态存储不连接MQ。候选查询只返回最多批次4倍（最大512）的聚合标识；随后按聚合进度、事件的顺序逐行FOR UPDATE SKIP LOCKED，重新核验当前头序号和到期条件，跳过忙行，不把过期快照当作领取许可。已分配=已消费时通过CASE避免最大long加1溢出；只有实际领取的最多128个信封进入结果，返回不可变列表。

租期基于已锁定行查询返回的数据库UTC时间，不依靠调用者时钟；每次领取产生新UUID竞争令牌，PROCESSING过期可接管，旧令牌失效。finish先以SKIP LOCKED取得匹配事件行，再用新的数据库语句时间检查租期、原事件/聚合/序号、令牌、失败次数和PROCESSING状态；忙行返回false，不等待后使用旧语句开始时刻误通过。成功只SENT，未推进consumed_sequence；非确认结果增加失败、按策略设next_retry_at或到阈值FAILED，并清空租约。所有修改随原工作单元提交或回滚；SQL1020/40001继续映射40900，仓储不自动重试。失败计数饱和于Integer.MAX_VALUE，避免损坏的大计数溢出。

本轮25项真实MariaDB测试通过，0失败/错误/跳过：新增租约9项，原Outbox5、序号7与序号并发4项回归。新增验证两聚合仅头可领/确认后仍等待消费证据、数据库时钟退避、FAILED不跳过头且其他聚合继续、过期接管/伪造未来时间无效、外层回滚、伪造领取字段拒绝、写事务与数据库租约约束、极大计数、两个仓储实例/独立连接在首事务持锁时领取互不重叠，以及完成操作对被锁行立即返回false。消费进度由测试SQL探针构造，仅验证资格判断，当前还没有生产消费者或推进逻辑；Application批次投递由E02.2c接入。

## Application有界Relay批次（NODE-E02.2c）

OutboxRelayApplicationService通过UnitOfWork、OutboxRelayRepository及可选OutboxPublisher端口编排，不依赖Infrastructure。runBatch必须从事务外调用；没有启用发布器时在领取前失败。每实例最多执行一个批次，原子标记仅限制本实例资源，跨实例资格继续由数据库令牌控制。request.batchSize是本次最多尝试次数：每次新工作单元只领取即将发送的一条，事务提交后调用MQ，再用独立工作单元记录结果；不预先积压持租等待的消息。

发布参数非法记为REJECTED，其他发布端口异常记为UNAVAILABLE，再由存储重试策略决定PENDING或FAILED；确认才能SENT。只有成功持久化结果才计入返回条数，返回值不表示成功消费。状态写入异常向调用方传播，未完成租约按到期恢复；中断保持标志并停止进一步事务/领取。日志只含安全事件/消息ID、trace、内部结果/错误类型，不输出正文或异常原文；发布阶段consumerGroup为空，真实消费组在后续消费者日志登记。

本轮Application共12项单元测试通过，0失败/错误/跳过，新增6项验证事务标志内领取/完成且事务外发布、最大尝试数、错误分类及未更新结果不计数、未启用或事务内入口拒绝、同实例容量/中断恢复、持久化失败传播与释放容量，以及发布中断后不再开启完成事务。测试的工作单元替身仅验证编排标志，不模拟实际数据库提交；真实DB/Broker联调由E02.2d完成。当前没有周期调度、任务接口或消费者，不将该入口当作已自动运行的Relay任务。

## Relay真实故障组合验收（NODE-E02.2d）

真实Starter在隔离MariaDB、Redis和RabbitMQ空间中运行，5项集成测试通过，0失败/错误/跳过。独立连接在发布前可读取已提交的PROCESSING租约，发布阶段没有活动事务；Broker正文保留高精度数值、原eventId及序号。两个聚合可独立投递，同聚合前序确认后仍等待消费进度；进度由测试SQL探针构造，不代表生产消费者已经交付。

故障验证覆盖删除绑定队列后的mandatory返回、实际拒绝连接、发布确认后数据库租约过期，以及Quorum队列容量触发真实否定确认。恢复拓扑/连接/容量后仍使用原消息身份重试；过期租约以新令牌接管，Broker可出现相同eventId的两份投递，后续消费者必须持久化去重。没有停止共享Broker或修改用户队列；测试连接、临时子上下文和专用拓扑已关闭/清理。E02.2完成，自动调度仍待E03，消费留痕与缓存补偿继续E02.3。

## 消费凭据与顺序存储（NODE-E02.3a）

V202610260003新增outbox_consumption，以eventId/固定消费组为主键，并约束聚合/序号/消费组唯一；保存数据库原始主题、发生时刻、载荷、trace及消费时间。现有迁移保持校验和。ERP_AUDIT只接erp.*，CACHE_INVALIDATION只接缓存失效；每类事件只有一个负责推进全局聚合进度的订阅。

OutboxConsumptionRepository在Application已有写工作单元中执行begin→副作用→complete。begin按进度、事件锁顺序使用SKIP LOCKED当前读，忙行或非头返回WAITING，已完成凭据返回DUPLICATE；收到信封必须与本地Outbox的全部字段、正序号及无损JSON值相符，未知或篡改事件拒绝。complete重新核验资格，同事务复制原始信封并推进consumed_sequence；不要求先SENT，允许Broker已收到但发布确认尚未落盘的正常时序。存储不自行重试或开启事务，快照1020/串行化冲突沿用40900，整个工作单元回滚后重试。

7项真实MariaDB测试通过，0失败/错误/跳过，覆盖审计原值与高精度、重复凭据、前序等待与其他聚合独立、组别、篡改及未知事件、完整回滚、事务/只读拒绝、独立连接竞争，以及旧快照冲突回滚后新事务识别重复。当前仅有存储端口，生产缓存消费用例和Broker监听仍待E02.3b/c，不将技术存储验收视为已自动消费。

## 缓存补偿与ERP留痕用例（NODE-E02.3b）

Domain的OutboxConsumer端口由OutboxConsumerApplicationService实现，Infrastructure后续仅依赖该端口。consume必须从事务外进入，一个UnitOfWork中依次核验资格、执行副作用、保存凭据和推进进度；返回CONSUMED/DUPLICATE时事务已经提交，WAITING不执行副作用。已有外层事务会被入口拒绝，避免后续监听器过早ack。消费trace使用现有关联标识规则并在finally恢复调用线程上下文；日志包含eventId、messageId、traceId、consumerGroup和结果，不记录正文。

ERP_AUDIT只保存原事件留痕，不增加业务副作用。CACHE_INVALIDATION严格解析现有CacheInvalidation契约并复用VersionedCache.invalidate及TTL/版本屏障。Redis效果与数据库没有分布式原子事务：Redis成功后数据库失败时凭据和顺序回滚，下次仍可失效；版本规则保证不会删去更新的数据。DUPLICATE在副作用前返回，不重置缓存标记或TTL。

本轮13项真实MariaDB/Redis集成通过，0失败/错误/跳过（原存储7项、新应用6项）。新增覆盖ERP原值/trace恢复、真实旧缓存删除与版本屏障/有界TTL、重复无效果、实际Redis操作后注入结果失败、凭据写入后注入失败并整体回滚、更新版本保护、非法载荷和后序等待、外层事务拒绝。故障由测试包装器在真实效果之后注入，不宣称模拟实际Redis断网；Broker监听与手动ack尚待E02.3c。

## 信封解码与确认适配（NODE-E02.3c1）

RabbitOutboxMessageCodec限制信封512KiB，要求持久JSON/UTF-8、7个字段、正long序号、安全trace，以及messageId/correlationId和四项关联头与正文一致。JSON严格拒绝重复字段、尾随内容、隐式文本/整数转换，使用BigDecimal保留原值。解码只是传输校验，Application中的存储仍需核对原Outbox信封。固定错误说明不携带原载荷或解析异常。

RabbitOutboxConsumerAdapter只依赖Domain消费端口，核验主交换机和实际路由与订阅匹配。应用提交后返回CONSUMED/DUPLICATE才basicAck单条；WAITING使用basicNack(requeue=true)原生延迟等待，实际解码/应用失败使用basicReject(requeue=true)增加失败计数；中断线程只退回消息。确认IOException向容器传播，不在结果未知后再reject。失败日志仅安全身份、组别及异常类型，不记录异常原文/正文。

13项单元通过，0失败/错误/跳过（新消费7、原发布6），用实际生产发布编码验证接收兼容与精度；通道/应用端口为可控替身，不作为Broker或数据库验收。尚未装配监听容器、启动线程或自动消费；真实生命周期由E02.3c2继续。手动确认语义参考[Spring AMQP配置](https://docs.spring.io/spring-amqp/reference/amqp/containerAttributes.html)，实际依赖保持4.0.2。

## 有界消息监听与退出（NODE-E02.3c2）

在已配置消息连接且STRATUM_MQ_ENABLED=true的基础上，设置STRATUM_MQ_CONSUMER_ENABLED=true才创建ERP和缓存两个固定监听容器；默认false，不创建消费工作池或监听线程。只开消费而未开消息时启动失败。两个容器依赖Domain的OutboxConsumer，不引用Application类型；拓扑初始化后通过Spring生命周期启动，不引入Quartz或周期Relay。

STRATUM_MQ_CONSUMER_CONCURRENCY默认1，允许1至4，表示每队列固定并发；STRATUM_MQ_CONSUMER_PREFETCH默认16，允许1至32。主连接通道上限必须至少为2×并发+1。独立工作池上限为2×并发+2，额外两个线程供容器停止回调使用，等待任务数同样有界；不能把所有线程分配给长期消费后让停止回调排队。单条解码512KiB，连接入站体积上限仍为2MiB，预取数量不等于精确字节内存上限。

容器使用MANUAL、关闭批量拆分及通道事务。确认传输异常终止当前消费通道以归还未确认消息，不重置共享连接，不追加相反确认。容器错误处理只记异常类型和固定组别。STRATUM_MQ_CONSUMER_SHUTDOWN_TIMEOUT默认5s，允许1至10s：退出先允许已预取处理在预算内结束，超时中断处理并关闭通道，未确认消息回Broker；不承诺强制终止不响应中断的任意用户代码。工作池随上下文关闭并限制退出等待。停止/回调语义核对[实际4.0.2容器源码](https://github.com/spring-projects/spring-amqp/blob/v4.0.2/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/listener/SimpleMessageListenerContainer.java)。

本轮监听测试6项及完整Starter回归2项通过，0失败/错误/跳过。监听测试含3项配置/资源边界、1项真实装配配合受控确认IOException、2项真实Broker流程：等待nack后的delivery-count仍0，reject后为1，第三次成功后队列无消息；实际预取2条后触发1秒退出预算，中断当前处理并最终归还两个原消息ID，连接和工作池均退出。受控消费端口不代表数据库业务消费组合，后者由E02.3d验证；完整Starter2项证明消息开启、消费默认关闭时仍无监听/自动Relay，原事务提交与回滚保持。E02.3c完成，DLQ持久化及人工重放尚待E02.4。

## 有序消费完整组合验收（NODE-E02.3d）

3项真实MariaDB/Redis/RabbitMQ组合通过，0失败/错误/跳过。完整Starter启用消费开关，由生产Application有界Relay领取、事务外发布和监听器手动确认；连续两条ERP事件保留原高精度载荷与顺序，缓存补偿实际删除旧值并阻止旧版本回填。消费进度由生产应用提交，不使用SQL探针推进。

跨实例用例在首实例真实提交后注入确认前失败，由真实Broker原生重投；停首实例监听后，第二独立消费上下文以独立数据库连接、Application事务代理和Broker连接识别DUPLICATE。实际CONNECTION_ID集合互不重叠、AMQP连接不同；凭据仍只有一份，缓存失效总计一次，重投不修改较新缓存或其版本标记。第二上下文共用真实Redis门面，验证持久化去重与接管，不把它描述成两套独立L1缓存或两个完整应用进程。

前序用例先在应用调用前注入一次实际消费失败，再受控返回WAITING；原生重试保留该消息，后序仍PENDING、该聚合进度为0，其他聚合成功推进。解除等待后，生产消费者提交前序，Relay才可投递后序并完成。故障探针均在生产用例前后，不替代数据库/Redis/MQ，不宣称进程崩溃或Broker断网演练。监听、第二上下文/数据库连接与消息拓扑均退出/清理。E02.3完成；DLQ持久化、人工重放及超限恢复由E02.4继续，自动Relay调度仍待E03。

## 死信证据存储（NODE-E02.4a1）

V202610260004新增outbox_dead_letter，保存原消息二进制、元数据JSON、解析身份声明、失败分类与计数、归档及最近收存时间。原体最多2MiB、元数据最多128KiB；非法UTF-8或空原体仍可作为INVALID_ENVELOPE隔离归档，身份字段为空。可解析RETRIES_EXHAUSTED归档保留原eventId/sequence/payload/trace，但这些仍是传输声明，未自动证明本地原事件存在，不作为重放授权。归档不修改Outbox状态或消费进度，不跳过前序。

Domain的Letter防御复制二进制并实现按字节值相等、安全摘要。存储端口只参与已有Application写工作单元；SHA-256指纹包含来源组、分类、原体及严格JSON字段排序后的元数据，长度前缀防止二进制边界歧义。同证据并发/重复收存返回首次归档ID，只更新最近时间、饱和收存次数及最高失败计数，保留首份原文；不同投递证据可独立归档。原迁移保持校验和。状态预留重放请求与恢复值，本节点没有实现重放或监听。

9项真实MariaDB测试通过，0失败/错误/跳过，覆盖原体与高精度原值/身份、原状态和进度不变、嵌套元数据字段顺序去重、不同来源组隔离、非法字节与空体、未知身份不创建业务进度、首次和重复回滚、写/只读事务、严格JSON、归档ID冲突及两行主键/证据双冲突整体回滚、2MiB实际存储与计数饱和、数据库身份完整性约束。并发用例使用不同CONNECTION_ID，在首事务持锁时以有界Future等待确认竞争事务尚不能完成，提交后两实例返回同ID且计数2。DLQ消费者的真实归档继续E02.4a2/a3，不能将当前存储测试描述为Broker死信收存完成。

## 死信归档应用入口（NODE-E02.4a2）

OutboxDeadLetterCollector由Application的OutboxDeadLetterApplicationService实现，供后续DLQ传输适配依赖Domain端口。archive必须从事务外进入，已有外层事务在写入前拒绝；单个UnitOfWork负责存储和重复计数，提交成功后返回稳定UUID。存储失败或返回空标识均在工作单元内抛出并回滚，不允许监听器提前ack。当前仍没有DLQ监听或重放入口。

应用只归档证据，不验证未知身份的业务授权，不创建或推进原Outbox/聚合进度。日志只含归档ID、安全事件ID、解析后安全trace、固定消费组/分类；非法或缺失trace使用新关联ID，finally恢复调用线程原上下文。原体、元数据及非法trace原文不进入日志，归档记录仍按原始证据保留。

6项真实MariaDB应用集成通过，0失败/错误/跳过，验证返回后独立连接可见提交、工作单元trace贯穿及恢复、重复稳定ID/不创建未知业务状态、写入后失败和空结果回滚、外层事务拒绝、隔离归档与不可信内容安全日志。故障由真实仓储调用后的受控包装注入，不当作数据库断网或Broker验收。两个DLQ的手动确认与有界生命周期继续E02.4a3。

## DLQ归档监听与故障保留（NODE-E02.4a3）

STRATUM_MQ_CONSUMER_ENABLED=true现同时启动两个业务和两个DLQ订阅，默认仍关闭。DLQ各固定并发1、prefetch1，独立工作池最多4线程/4项等待，其中两个线程预留生命周期回调；沿用消费退出预算。主连接通道至少为2×业务并发+3，默认16可覆盖所有允许并发。归档监听只依赖Domain的OutboxDeadLetterCollector，返回提交成功后单条ack，归档失败用nack(requeue=true)原生延迟等待；确认传输异常终止本通道，不重置共享连接。

两个DLQ保持Quorum、reject-publish容量上限及无TTL，增加原生all延迟、明确x-delivery-limit=-1；归档故障或连接反复退出不得触发DLQ默认次数淘汰而丢失证据，业务队列的既定失败上限不变。延迟沿用topology.retry-min/max（默认1s/30s）约束故障重试速率，容量仍有界。配置与既有队列不等价时启动失败，不自动删除或清空既有消息，需人工迁移队列参数；本轮只使用受守卫的测试空间。依据见[RabbitMQ 4.3原生重试与投递计数](https://www.rabbitmq.com/docs/4.3/quorum-queues)。

归档保存完整原体及已登记传输头、首次死信原因/队列和可见死亡事实的有界摘要；超长头保存最多1024字符预览、字节数和SHA-256，不把不可信头或原体写入日志。动态deliveryTag、消费者标签及本次DLQ重投次数不参与证据指纹；failure_count只登记Broker当前可见计数，不从配置猜测原业务失败次数。来源或契约不符的原体隔离归档。发布器每次实际发送新增stratum-publication-id UUID，eventId/messageId与sequence不变；同次发布的DLQ重投稳定去重，不同发布尝试可分开留痕，尚非人工重放入口。

本轮13项单元与26项相关集成通过，0失败/错误/跳过。集成包括新归档5、原拓扑6/发布4/默认关闭完整Starter2/监听6/有序消费组合3。新验证两个业务队列真实原生超限归档、归档前受控故障时保留消息并恢复、真实归档提交后确认前故障稳定去重、测试重投同一已落盘信封区分新发布、非法字节隔离，以及DLQ实际22次reject后仍取回并确认原消息。故障包装不宣称实际数据库断网，测试主动发布仅用于故障与契约探针；两个工作单元效果不被替代。全部监听/连接/工作池已随测试上下文退出。E02.4a完成，人工重放继续E02.4b，自动Relay仍待E03。

## 人工重放请求与持久化意图（NODE-E02.4b1）

V202610260005新增outbox_replay_request请求回执及outbox_replay_delivery独立投递意图。OutboxReplayRepository只在Application已有写工作单元登记，不调用MQ；原Outbox的eventId、sequence、payload、trace及SENT历史均不改变。请求trace单独记录。每份归档只有一个意图，同事件/消费组最多一个PENDING或PROCESSING意图；已发送意图不倒退原事件状态，不同发布归档可显式恢复。

登记前复用消费存储核对本地原事件、订阅和聚合头，并独立严格解码归档原体（UTF-8、7字段、整数序号、无重复字段/尾随数据、全部身份及无损payload）。隔离、未知或篡改证据拒绝；忙行/非头冲突返回40900。原投递尚为PENDING/PROCESSING时不另建重放；SENT/FAILED可恢复，已有实际消费凭据则返回ALREADY_CONSUMED且无新发送。归档不作为权限授权，外部管理入口仍待后续节点。

同requestId保持首次回执，不能更换archiveId；新请求针对已有意图返回ALREADY_QUEUED。失败意图可用新人工请求恢复PENDING，复用意图ID并重置本轮重试计数，原失败数留在新请求previous_failures。请求回执是历史登记结果，不是当前投递/消费状态。REGISTERING只在同一未提交工作单元内使用，任何失败都必须回滚整个工作单元；唯一键/1020/串行化冲突不在仓储内重试。锁定顺序先聚合/事件，再请求登记和归档/意图，避免先持归档锁再等待消费进度。

11项真实MariaDB测试通过，0失败/错误/跳过，覆盖原身份/高精度/SENT和进度不变、同请求稳定返回/不同目标拒绝、完整回滚、已消费无新意图、隔离/未知/非头/篡改原体、原投递进行中拒绝、失败意图恢复及历史计数、不同归档的活跃意图互斥、事务/只读和关联值边界，以及两个独立连接持锁竞争409后新工作单元复用唯一意图。SENT/FAILED等前置状态部分由测试SQL探针构造，不能作为真实Broker发布确认或重放投递验收；Relay适配继续E02.4b2，应用入口继续b3。

## Relay重放领取与恢复（NODE-E02.4b2）

现有OutboxRelayRepository同时领取原投递及独立重放意图，Application批次端口保持不变。两来源各取最多4倍请求批次的候选，在SQL中按到期时间合并再限4倍；最多1024条中间候选、512个返回聚合标识，实际信封最多128条。原投递与重放没有固定优先级，避免新原投递持续饿死较早重放。每聚合仍先持进度锁，只允许尚未消费的头序号；重放必须匹配本地eventId/aggregateId/sequence/消费组，原事件处于SENT/FAILED。

重放信封从原Outbox读取，不从归档原体发送，保留全部原身份及高精度载荷。租期用数据库时刻分配，新竞争令牌与意图PROCESSING同事务提交；外层仍在事务外发送。完成时先按事件/令牌尝试原投递，非原令牌则按独立意图当前读锁核验，再使用新SQL数据库时间校验期限及重试计数。confirm只标独立意图SENT并登记sent_at；原Outbox状态不变，不表示已消费。失败沿用封顶退避与次数上限，超限FAILED仍阻塞前序；过期可接管，旧令牌、忙行或证据不符返回false，计数饱和不溢出。

28项真实MariaDB集成通过，0失败/错误/跳过（新重放领取8、原领取9、人工登记11）。新增核验原信封与SENT历史、消费前序屏障、原/重放公平候选、失败退避与其他聚合、过期接管和伪造截止、领取/完成回滚、跨实例跳锁互斥、忙行/伪造计数及极值、损坏来源字段拒绝。原状态和发布结果部分由SQL/参数探针构造，仅证明存储协议，不当作Broker重放或应用入口验收。后续b3接入人工Application入口和消费恢复关联，c执行真实超限/重放完整闭环；自动调度仍待E03。

## 人工重放应用与消费恢复关联（NODE-E02.4b3）

OutboxReplayRequester由Application实现，入口拒绝已有外层事务，使用一个UnitOfWork登记人工请求和投递意图，提交后才返回历史回执。请求trace与原事件trace分开，调用线程上下文在finally恢复；日志仅请求ID、归档ID、意图ID、请求trace和结果。此入口不发送MQ、不提供HTTP端点或代替未来管理接口的RBAC。

消费complete在原凭据/进度工作单元内，把已经验证并关联该事件/组的重放归档标记RESOLVED，同时将独立意图标记RESOLVED、记录resolved_at并清理租约。原Outbox的SENT历史保持不变；未被人工请求核验的其他归档不因此变成已授权证据。消费发生在发布最终状态之前时，清理令牌使迟到finish返回false，sent_at仍按实际持久化confirm证据保留，不能用消费成功伪造发送确认时间。任何随后失败都回滚凭据、进度及恢复关联；Redis外部失效仍按既有版本屏障幂等重试。

41项集成通过，0失败/错误/跳过：新增重放应用6，原重放领取8/人工登记11/消费存储7/缓存及留痕应用6/真实Broker有序组合3。新增验证返回后独立连接可见提交、没有发布器时仍可登记、上下文恢复、消费与恢复同事务、历史回执与新已消费结果、领取中消费先完成且迟到确认不覆盖、全关联回滚及重试、登记后失败/空回执回滚、外层事务拒绝。前置SENT与直接消费调用是技术夹具，不当作真实Broker重放验收；完整超限→归档→人工重放→后序恢复继续E02.4c。E02.4b完成，调度仍待E03，任务业务范围仍待用户确认。

## 真实死信与人工恢复闭环（NODE-E02.4c）

4项完整Starter/真实MariaDB/Redis/RabbitMQ组合通过，0失败/错误/跳过。事件从PENDING经生产有界Relay及发布确认进入原生消费失败/超限，再由DLQ监听和实际应用事务归档，人工Requester登记独立意图后继续经同一Relay发送和消费恢复。投递、消费、归档和恢复状态均由生产路径推进，没有SQL状态探针或直接调用消费者伪装消息收发。

ERP信封用例验证失败前序阻塞后序而其他聚合完成；人工重放后连续两序号只留各一份高精度原文/trace消费凭据，原SENT不倒退，关联意图和归档RESOLVED。缓存用例从生产CacheUpdateCoordinator登记并提交后注入缓存不可用，实际旧Redis值仍在；该补偿经真实DLQ/人工重放后成功失效一次，最低版本屏障与TTL保留，重复人工请求不重置标记或产生第二次效果。包装故障不宣称Redis断网。

再次失败用例让第一次重放真实超限，新的publication-id形成新归档周期；针对新归档再次人工恢复，两个关联意图/归档最终RESOLVED，业务eventId/sequence不变且消费凭据只有一份。非法原体由测试故障入口直接注入Broker，真实隔离后应用请求返回40000且没有投递意图，不作为生产绕过Outbox入口。自身监听、连接、线程和拓扑均退出/清理；用户服务保持运行。E02.4完成，E02.5再全工程回归，不以这4项替代全部模块验收。

## 消息阶段收敛（NODE-E02.5）

2026-10-26执行 `.\gradlew.bat --no-daemon test :stratum-gateway:integrationTest :stratum-starter:integrationTest --rerun-tasks`，39个任务全部重新执行，退出码0。210项单元（Application12、Common9、Domain67、Gateway33、Infrastructure25、Interface10、Query21、Starter33）及542项Starter、10项Gateway显式集成，共762项通过，0失败/错误/跳过；Job尚无测试，不将NO-SOURCE计为通过。使用独立MariaDB11.8/Redis8.2及现有RabbitMQ4.3.6的专用测试用户/vhost。

E01/E02完成。运行时先启用消息连接并提供专用Broker凭据，再按需启用消费；连接与消费均默认关闭。两个业务消费者及两个DLQ消费者随上下文有界关闭；自动Relay尚未接入，显式调用OutboxRelayApplicationService.runBatch才推进待发送记录。人工恢复通过OutboxReplayRequester.request登记持久化意图，当前没有HTTP入口，后续仍由Relay发布原事件身份。登记回执表示请求结果，SENT只表示发布确认，RESOLVED及消费凭据才证明消费完成。

启动前备份数据库并核对Flyway：序号迁移遇到非空未知历史Outbox会显式失败，不能自行猜测顺序；既有Quorum队列参数不兼容时不会自动清空或重建。业务失败按既定上限进入DLQ，归档故障保留有界队列中的证据并原生延迟重试；满队列会拒绝发布，需排查和恢复，不能删除证据绕过。每聚合死信只阻塞自身后序；人工重放必须核验本地原事件，隔离/未知/篡改归档不能执行。所有已提交迁移校验和保持不变。

本轮测试应用、Gateway子进程、监听连接和工作池已退出；独立测试数据库/Redis及专用消息空间仅供后续连续验证，最终结束前清理本轮资源。E03继续Quartz/ShedLock调度，不将此文档收敛计为新增业务实现。

## 调度持久化表与依赖（NODE-E03.1）

Quartz使用项目Boot4.0.5 BOM固定的2.5.2，ShedLock7.10.1版本约束集中在根工程；两者均为Apache-2.0，不要求商业服务或按量计费。Job仅引入Quartz/ShedLock Spring和Spring Context，Infrastructure引入Quartz/Spring Context Support及ShedLock JDBC适配；Domain没有框架依赖，尚未创建任何调度Bean或线程。ShedLock支持Spring7的维护声明和许可见[官方仓库](https://github.com/lukas-krecan/ShedLock)，Quartz许可及原始SQL来源保留在迁移头。

Flyway V202610260006新增11张STRATUM_QRTZ_技术表及stratum_shedlock。Quartz2.5.2官方MySQL/InnoDB结构去除DROP/commit并固定前缀及精确字符排序，ShedLock名称主键唯一、租期保留毫秒。不会改写已提交迁移，也不执行每次启动清表的Quartz初始化脚本。

独立MariaDB11.8上SchedulerSchemaIntegrationTest共3项通过，0失败/错误/跳过；验证完整迁移及未启用时无心跳、触发器外键/重复键/父记录删除保护、锁名唯一与毫秒持久化。Job compileJava为NO-SOURCE，仅依赖装配验证，不视为Job测试通过。生产调度装配与实际竞争继续E03.2。

## 显式调度器装配（NODE-E03.2）

STRATUM_JOBS_ENABLED缺省false，完整Starter没有Scheduler或Job LockProvider；设为true后SchedulerConfiguration在数据库初始化完成后创建JDBC集群调度器，固定STRATUM_QRTZ_前缀、useProperties=true（JobDataMap仅字符串）、每次生成UUID实例ID。STRATUM_JOBS_NAME默认stratum，同集群一致，允许1至64个字母数字/点/下划线/连字符；WORKERS默认2、允许1至8。每次最多领取一个触发器，线程数固定，无业务任务在此自动登记；数据库故障重试5秒、集群检查5秒、misfire阈值60秒。多实例Quartz仍要求主机时间同步。

ShedLock使用stratum_shedlock和数据库UTC时间，技术协调独立于业务工作单元。EnableSchedulerLock采用方法代理与类代理，Quartz JobFactory只查找已有唯一Spring Job组件，保留注解代理，不能通过数据库类名任意创建实例；Job必须保持无共享执行状态。动态锁名需按ShedLock语法使用完整SpEL包装，并以实际落库锁名验收。表由Flyway维护，不开启Quartz初始化或覆盖已有任务定义。

4项新集成与3项表结构回归共7项通过，0失败/错误/跳过。真实MariaDB验证默认关闭/非法边界、两个不同Scheduler实例共享集群JobStore且单个触发只执行一次、重启后读取持久Job、两独立Spring代理竞争同名ShedLock时跳过及释放后恢复。每个子上下文关闭后在5秒内核对其调度/集群/工作线程退出。探针是无业务副作用的测试Job，竞争同步只使用测试信号，实际互斥使用数据库。当前未交付生产任务、运行中任务超时中断、执行记录或dead_job，继续E03.3/4/5；关闭设置不无限等待在途任务，具体可中断执行器仍需后续验收。

## 任务执行值对象与端口（NODE-E03.3a）

新增Domain JobDefinition、JobExecutionRepository及JobRecoveryRepository契约，包含有界定义/重试、不可变登记和领取证据、固定结果与人工恢复请求。使用既有CorrelationIdPolicy校验trace，未引入框架；正文、幂等键与令牌从执行默认摘要中隐藏。明确显式键/计划时刻键、数据库时间精度、退避上限和保留历史的恢复周期，详见SPEC-009第10节。

Domain全模块76项测试通过，0失败/错误/跳过；新增9项覆盖互斥计划、时限/代码/数据库时间范围与精度、极大次数退避、完整输入/摘要、UTF-8字节与代理字符、身份/trace/键规则、领取次数/租期/周期及恢复请求。当前只有契约，没有任务仓储实现、执行表或实际调度任务；E03.3b继续迁移。

## 任务执行历史表（NODE-E03.3b）

V202610260007新增job_execution、job_attempt、dead_job、job_replay_request，均为InnoDB。保存不可变输入及历史关联，按任务代码/幂等键去重，限制参数/trace/计划、次数/周期、状态与租约一致性；枚举以BINARY精确比较拒绝尾随空格，避免ascii_bin的填充语义；死亡记录复合关联实际失败尝试，恢复记录绑定死亡和执行身份。当前没有任务仓储或生产任务，不把表结构视为调度执行能力。

JobHistorySchemaIntegrationTest新增6项真实MariaDB测试，连同迁移3项及调度装配4项，共13项通过，0失败/错误/跳过。覆盖迁移完整性、双唯一键、状态/租期/计数、正文/计划/trace边界、同周期失败原因/恢复目标外键、状态/规则/结果/原因尾随空格拒绝及全部历史表同事务回滚。使用SQL夹具明确区分仓储验收；用例只清理自己的随机身份。调整未提交新迁移期间，仅在已核对身份且四表均为空的本轮独立测试库重建这四张新表，随后从Flyway重新执行验证；所有已提交迁移校验和未变，用户数据库未触及。E03.3c继续实际登记/领取适配。

## 幂等登记与数据库竞争领取（NODE-E03.3c）

JobExecutionRepositoryImpl只参与已有Application写工作单元。使用同一DataSource的专用JdbcTemplate设置5秒查询预算，不修改全局客户端或新增连接池。登记严格校验JSON重复键/尾随内容和真正的Quartz Cron语法，大整数/小数按无损JSON值比较；双唯一键碰撞后以当前读核验UUID和业务键，禁止覆盖两条既有记录。等价登记保留首次UUID、原JSON和trace，输入不一致返回40900。

到期候选按任务代码分开查询待运行和租约过期两分支，各限请求上限后合并排序，最多256个中间身份、最终1至128个不可变候选。候选只是提示，claim仍以FOR UPDATE SKIP LOCKED当前读核验；锁定后另取数据库UTC时刻，不用调用者时钟或等待前的语句时间。领取原子增加累计尝试并生成token、尝试历史，租期为timeoutSec加30秒。已过期尝试先登记LEASE_EXPIRED、耗时和失败数，再进入持久化退避或同周期dead_job，不在该调用中立即重跑。计数/版本上限拒绝溢出，竞争1062/1020/1205/40001须由整个工作单元回滚后重试。

13项新仓储与6项历史表回归共19项真实MariaDB测试通过，0失败/错误/跳过。验证高精度/原值、等价幂等与双唯一冲突、JSON/Cron拒绝、事务/只读、未来与有限候选、数据库时间/尝试、登记/领取回滚、过期退避和超限、过期归档全回滚、两独立连接跳过持锁行及极大计数。过期位置由自身SQL时钟夹具设置，不宣称实际等待60秒或执行处理器；新token取代旧token的存储证据已核对，真正的迟到完成拒写继续E03.3d。尚无正常完成、人工恢复或自动执行入口。

## 任务完成与人工恢复存储（NODE-E03.3d）

JobRecoveryRepositoryImpl与登记适配共享同组技术表的行映射、数据库时钟和原子结果操作，仍只在Application已有工作单元中写入。完成核验完整领取证据并在锁后重新取数据库时间，清除租约并留存固定结果/耗时；失败按次数指数退避，达到上限同事务关联dead_job。内部过期处理复用相同结果写回，外部完成不能伪装LEASE_EXPIRED。

人工恢复按执行行、死亡行的固定顺序锁定；新请求只恢复当前死亡周期，独立请求UUID保存历史回执。保留原执行/参数/初始trace和累计尝试，增加周期、重置周期失败并更新当前trace；等待执行及恢复请求都不表示成功。后续失败保留新死亡周期，实际完成成功才关联该执行所有待恢复历史为RESOLVED。旧请求去重保持首次身份，新请求指向旧/已恢复目标409；未知死亡404，计数/版本不能前进时拒绝。

新增13项完成恢复测试，连同13项登记领取及6项表约束共32项真实MariaDB集成通过，0失败/错误/跳过。覆盖成功唯一完成、失败/超时/中断分类与精确退避、全证据伪造、匹配但过期和已接管令牌拒绝、完成/死亡/恢复回滚、跨周期保留和最终关联、目标更换/未知、极大计数、事务/只读及两个独立连接完成/恢复竞争。结果由测试端口输入，过期/退避位置及极大计数用自身SQL夹具，不宣称处理器或真实Quartz重试已交付。E03.3e继续Application入口。

## Application任务状态入口（NODE-E03.3e）

JobExecutionApplicationService通过Domain登记/完成端口与现有UnitOfWork编排，不依赖Infrastructure。register、due、claim、finish、replay全部拒绝活动外层事务，返回前短工作单元已提交；实际处理器不得在该工作单元内运行。登记、领取和恢复的非空结果校验及候选不可变复制发生在提交前，端口违约不会提交部分写入。

登记/完成/恢复使用对应输入的安全trace；尚未读取执行输入的技术候选/领取使用已清洗的调用trace。所有成功及失败路径在finally恢复原线程上下文，无原上下文时清理临时值；存储中的任务trace仍来自不可变登记/恢复周期，不受调用线程污染。当前服务不启动调度线程、不执行任务，也不暴露HTTP端点。

新增8项真实应用/数据库集成，连同既有32项任务存储与迁移回归共40项通过，0失败/错误/跳过。验证独立连接返回提交可见、完整入口调用、登记/领取/恢复写入后的异常及空结果回滚、完成与历史恢复关联回滚、所有入口外层事务拒绝，以及安全上下文和清理。故障包装调用真实仓储后再注入，不替代真实事务或宣称数据库断网。E03.3完成；实际执行/中断超时与Quartz重试继续E03.4。

## ShedLock有界续期（NODE-E03.4a）

默认关闭调度的行为保持不变。显式启用后，jobLockProvider将现有数据库时间JdbcTemplateLockProvider交给ShedLock7.10.1 KeepAlive，并由BoundedJobLockProvider限制在途锁及私有续期池。workers为1至8、在途锁上限2×workers+2，所有SQL继续参与ShedLock自身协调事务并设置5秒语句预算；不打开业务工作单元。初始租期至少30秒，库约半租期续期，详情见[官方KeepAlive说明](https://github.com/lukas-krecan/ShedLock#keepalivelockprovider)。

内部ThreadPoolTaskScheduler由提供者拥有、关闭取消续期并有界等待，不公开为Executor Bean，避免改变项目Boot4.0.5的应用执行器自动选择。只有真实解锁结束才释放一次本地名额；该名额不提供分布式所有权，数据库锁仍必不可少。后续执行器须先停自己的实际工作，再释放锁提供者及数据源。

2项新真实续期测试及既有调度4项/迁移3项共9项集成通过，0失败/错误/跳过。实际等待超过原30秒截止（新续期测试约32秒），数据库租期确已推进且另一提供者仍无法取得；释放后可取。另验证单线程对应4个锁名额、超额不落库、重复释放不超发、短租期/非法名拒绝不泄漏，以及内部Executor不暴露、全部调度/续期线程退出。没有SQL时间探针或模拟提供者，不将此验收视为处理器/Quartz失败闭环完成；下一E03.4b。

## 任务处理器契约与注册（NODE-E03.4b）

Application新增JobHandler端口，Job新增固定有界注册表：最多64个安全唯一代码，保存周期定义快照，周期只能是匹配代码的CRON/SCHEDULE_TIME，Quartz校验真实语法。未知处理器拒绝，不根据外部输入反射装载类型；无处理器不会生成默认任务。执行器后续通过Application入口控制任务状态和真实处理，不让Job直接访问数据库。

Job为后续显式配置绑定与任务日志接入项目BOM管理的spring-boot-autoconfigure及既有SLF4J API，无新版本号或商业组件，也没有采用Quartz Starter的隐式调度器。当前注册表构造不创建线程或执行处理器。

Job首次5项实际单元测试及Application12项回归共17项通过，0失败/错误/跳过；验证空/未知、快照/不可变排序、64个上限、非法/重复/缺失代码以及周期归属/类型/幂等规则/真实Cron不一致拒绝。本节点未运行集成测试，不把注册契约记为实际任务执行；下一E03.4c。

## 单次执行截止协调（NODE-E03.4c1）

JobRunControl只协调单个进程内私有虚拟线程的启动、截止信号、收尾和最终完成；不访问数据库、不提供跨实例互斥，也不启动调度器。运行阶段的中断投递与收尾阶段判定在同一短临界区，收尾清理中断并保留代理解锁后的恢复责任；未领取路径同样封闭启动阶段。JobExecutionResult表达技术调用结果，其中STILL_RUNNING/STATE_UNCERTAIN不能当作持久化成功。

新增7项真实虚拟线程/中断单元与原注册表5项共12项通过，0失败/错误/跳过。覆盖实际中断唤醒、迟到信号拒绝、清理前清除及之后恢复、启动前停止、未领取清理、单调截止交接、无领取完成及唯一线程/重复交接保护。信号由用例主动投递，领取值为历史技术夹具，未验证真实超时预算经过、DB/ShedLock或处理器；所有测试线程有界退出。E03.4c2继续实际装配与集成。

## NODE-E03.4c2 实际执行边界

任务显式启用时装配私有有界虚拟线程执行器及ShedLock工作器，不暴露通用Executor Bean；默认关闭无运行资源。参数`stratum.jobs.workers`与Quartz共用1至8容量，默认2。实际处理在状态工作单元外运行，同代码在不同实例仍由数据库锁互斥；超时只请求中断，未实际退出继续占用容量和锁。调用分类不代替数据库执行状态；关闭顺序为实际执行器、锁续期、数据源。

本节点新增6项真实集成验证：事务外处理/trace恢复、失败死亡和日志脱敏、实际超时中断、忽略中断时容量/跨实例锁保持、关闭提交与实际线程退出、预中断/未知身份拒绝。处理线程保留实际引用验退出；平台线程快照仅用于Quartz/续期退出，不用它推断虚拟线程生命周期。完整Quartz触发与周期Relay尚待后续节点。

## NODE-E03.4d 固定调度触发

`stratum.jobs.enabled=true`时，已登记处理器自动获得每秒一次的到期驱动；周期处理器额外按其UTC Cron登记原计划时刻，初始参数{}。重启和双实例复用同一JDBC定义；同键配置冲突或遗留已移除的处理器/周期定义时启动失败，不自动覆盖或删除。任务历史与重试资格继续使用Application及真实数据库，不把Quartz触发次数当作业务处理次数。

新增真实Quartz JDBC测试核对双实例失败后退避成功、原WAITING重启执行、Cron身份/参数、持久化定义冲突与旧周期拒绝，以及实际Quartz/工作器的关闭提交和线程退出。关闭协调在Bean销毁前完成，锁提供者及数据源保持可用；周期Outbox Relay尚待E03.5。

## NODE-E03.4e 死亡与实际进程故障

本节点在既有真实Quartz测试上增加3个组合场景：连续失败死亡及原身份/幂等请求人工恢复；真实一秒超时中断死亡后恢复；独立完整应用JVM实际进入处理后被强制结束，两个存活调度实例等待真实租期，记录LEASE_EXPIRED并退避重试成功。保留历史尝试、原参数/初始trace和恢复trace，未修改数据库时间或锁截止。

故障进程夹具位于生产组件扫描根外，仅连接显式本机stratum_it数据库，不部署服务。使用测试JDK及真实类路径，Java参数文件避免Windows长度限制；堆256MiB、直接内存64MiB、处理器配额2。仅结束自身Process句柄并有界等待，子入口另有三分钟后备退出期限；结束后清理自有固定临时文件，提前启动失败保留临时诊断日志。主测试上下文及其线程也在验收后关闭。

## NODE-E03.5a1 消费凭据与原事件状态

已增加CONSUMED状态及其来源字段，保留有效发送租约供后续真实确认；已过期租约和未确认失败/待发送状态在消费提交时转为CONSUMED，保留原状态与失败次数。旧滞留记录的有界对账仍属于E03.5a2。本节点76项Domain单元与21项真实MariaDB/Redis/RabbitMQ消费集成测试通过，零失败或跳过；覆盖迁移、合法来源、事务回滚、有效/过期租约、模拟确认、失败来源、旧SENT与版本上限。已有滞留记录仍待E03.5a2对账。
