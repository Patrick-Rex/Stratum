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

QuerySpec.create 接收服务端字段白名单，将外部字段名转换成枚举，再由具体 QueryHandler 映射至 ORM 列。分页复用 BasePageRequest（默认 20，最大 200）；最多 3 个排序、20 个条件，文本参数最多 512 字符。支持精确、包含及同类型闭区间，未知字段、非法操作、类型不符或倒置区间均被拒绝。集合与参数类型保持不可变；包含查询的字面通配符转义和 SQL 参数绑定已由主数据真实库测试验证，Query / QueryHandler 已定义类型契约，QueryResult 复用 common 的 page、pageSize、total、items 并拒绝超量或不一致的结果；QueryContext 显式携带不可变身份与权限快照，必须由服务器构造，不能从 HTTP 请求体采信。QueryGateway 已按请求精确类型统一路由，启动时拒绝重复类型与 queryCode；执行时核对结果分页、显式传递并恢复 traceId，拒绝隐式加入活动事务。`stratum.query.duration` 按注册的 queryCode 与 success/error 记录耗时；`observability.query.slow-threshold-ms` 默认 300，慢查询及失败输出警告。当前已提供主数据HTTP查询，库存HTTP查询也已接通，订单HTTP查询已于C03.6f接通，身份认证与数据权限仍待 Phase D。

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
