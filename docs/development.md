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
| stratum-interface | ApiResponseFactory、HTTP 请求关联过滤器与分派注册 |
| stratum-application | Command / CommandHandler / UseCaseResult、工作单元事务代理、OperationLog 契约、CommandGateway；OutboxEventWriter 在已有工作单元内落盘领域事件 |
| stratum-query | QuerySpec、Query/Handler/Result/Context、QueryGateway 与 Spring 装配；统一路由及查询指标日志 |
| stratum-starter | 启动类、全局异常处理、国际化资源、Web 与 Actuator 依赖 |
| stratum-infrastructure | 已引入 Easy-Query 3.3.1（Boot 4 starter / MySQL 方言 / 处理器）、MariaDB JDBC、Flyway、Redis、Caffeine；已配置数据源与 Flyway；NODE-B03.2b 已验证真实 MariaDB 提交、回滚和嵌套事务 |
| stratum-gateway / stratum-job | Gradle 模块骨架 |

Domain 源码未导入框架，模块直接声明 common 依赖；根构建统一配置的 Lombok 编译期依赖同样作用于 Domain，不能将“源码无框架导入”表述为“所有配置均无第三方依赖”。

Easy-Query、MariaDB JDBC、Flyway、Redis、Caffeine 已接入依赖，已完成启动装配，事务行为验证见 NODE-B03.2b；本地JWT认证、会话撤销及管理员应用用例已实现；RabbitMQ、Nacos、Quartz、ShedLock、网关路由和观测组件仍为后续目标设计。选型约束见 [技术架构](技术架构.md) 和对应专题文档。

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

健康状态包含数据库与 Redis。本地 Caffeine 客户端默认最多 10000 条、写入后 30 秒失效，可用 `stratum.cache.local.maximum-size` 和 `stratum.cache.local.ttl` 调整；Cache Aside、空值保护、single-flight、分布式锁及补偿落盘已通过 NODE-B05 验收；补偿投递与执行仍待 Phase E。OpenAPI JSON（`/v3/api-docs`）与 Knife4j 页面（`/doc.html`）已接入并通过真实HTTP端点测试；业务响应文档已通过 C02.4b 的真实HTTP与浏览器验收。当前尚无 Prometheus 注册表或追踪后端，`/actuator/prometheus` 尚不可用。

当前没有 `docker/compose/`，本轮不实施部署编排。可以连接现有 Docker 服务，也可创建独立测试容器；不要复用其他项目的业务库或清空共享 Redis。集成测试使用独立数据库及带 `stratum` 前缀的键。

结束时在启动终端按 `Ctrl+C`，确认应用退出并清除当前终端密码变量。自动化验证在 finally 中关闭自己启动的进程或容器，保留用户已有服务。

## Outbox 写入约定

写用例通过 CommandGateway / UnitOfWork 开启事务，在保存聚合后调用 `OutboxEventWriter.append(event, "erp.material.created")`。写入器使用 MANDATORY 事务传播；事务外调用被拒绝，序列化失败或重复事件使事务回滚。事件信封包含 eventId、eventType、aggregateId、occurredAt、payload、traceId，UUID 归一化，JSON 数据上限 256 KiB，数据库时间按 UTC 保存。

Outbox 当前只做可靠落盘与状态约束；Relay、发送确认、重试、顺序消费与幂等属于 Phase E，不能把写入成功称为消息已送达。

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

更新失败会降级为带版本屏障的失效；Redis 完全不可用时记录 traceId、缓存键与事件 ID，保留已提交的业务结果与补偿意图。删除场景使用 invalidateAfterCommit，最低版本必须高于被删除对象的旧版本。当前已验证补偿落盘，Relay 和消费执行仍待 Phase E，不能宣称自动补偿已运行。

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
