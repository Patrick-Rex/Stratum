# Stratum 上下文快照（CONTEXT）

> **用途：** 为每次 AI 会话提供项目当前状态的快照，防止上下文从零构建带来的偏移。
> **维护者：** 每个开发节点完成后，由人工（或在 AI 监督下）更新本文件。
> **AI 读取时机：** CONSTITUTION.md 之后、FORBIDDEN.md 之前（执行五步协议的 STEP 2）。

---

## 1. 当前阶段

```
阶段：Phase C（读模型与接口）
当前节点：NODE-C02.2（待开始）
上次会话日期：2026-10-24（NODE-C02.1 验收）
项目状态：NODE-B01 已完成，Domain 核心抽象已建立（AggregateRoot、Entity、ValueObject、Repository、DomainEvent），domain 模块直接声明 common 依赖，源码无框架导入；根构建统一配置 Lombok 编译期依赖；Phase A 与 Phase B 已完成；Phase C 进行中
```

---

## 2. 已完成节点

2026-06-28 及以前为历史验收；2026-10-23 后续节点记录本轮实际检查。当前配置与实现范围见 [开发与迭代](development.md)。

| 节点 | 描述 | 完成日期 | 验收状态 |
|------|------|----------|----------|
| NODE-A01 | 初始化 Gradle 多模块骨架 | 2026-04-07 | 通过（`gradlew projects`、依赖方向检查） |
| NODE-A02 | 建立 starter 启动骨架与基础配置加载 | 2026-04-07 | 通过（`gradlew build`、`bootRun`、`/actuator/health`） |
| NODE-A03 | 建立公共基础模块（ApiResponse、错误码、traceId 工具） | 2026-04-08 | 通过（`:stratum-common:build`、`:stratum-interface:build`） |
| NODE-A04 | 建立统一异常处理与多语言消息基础设施 | 2026-04-09 | 通过（`:stratum-common:build`、`:stratum-starter:build`） |
| NODE-A05 | 建立基础扩展类与帮助类复用基线 | 2026-04-09 | 通过（`:stratum-common:build`、`:stratum-application:build`、`:stratum-query:build`、`:stratum-interface:build`） |
| NODE-B01 | 建立 Domain 核心抽象 | 2026-06-28 | 通过（`:stratum-domain:build`、依赖方向与源码检查无框架依赖） |
| NODE-B02.1 | 建立写命令、工作单元与审计契约 | 2026-10-23 | application build 退出码 0；契约无框架导入；本子节点不含行为实现 |
| NODE-B02.2 | 完成统一命令入口，父节点 NODE-B02 完成 | 2026-10-23 | application test 6项通过、0失败；Lombok 1.18.44 与 JUnit 工具链重新编译成功 |
| NODE-B03.1 | 接入持久化与缓存依赖并统一 Boot 版本约束 | 2026-10-23 | starter build 与 application test 退出码0；6项测试通过；依赖仅接入技术模块，不向Domain引入框架 |
| NODE-B03.2a | 完成数据源、Flyway 与 Application 事务装配 | 2026-10-23 | starter bootJar退出码0；独立MariaDB11.8完成1条迁移；连接独立Redis后health为UP；应用进程已停止 |
| NODE-B03.2b | 验证真实 MariaDB 工作单元事务行为并固化 ERP 首期范围 | 2026-10-23 | starter integrationTest退出码0；8项真实MariaDB测试通过，覆盖迁移、提交可见性、运行时异常/Error/SQL失败回滚及嵌套事务 |
| NODE-B03.3a | 建立可运行 Easy-Query 仓储测试模板 | 2026-10-23 | 6项真实MariaDB测试通过、零跳过；覆盖CRUD、事务回滚、事务外拒写、并发和过期版本冲突、分页及参数绑定；APT代理生成成功 |
| NODE-B03.3b | 完成 Redis/Caffeine 客户端验证，父节点 NODE-B03 完成 | 2026-10-23 | starter integrationTest16项通过、0失败/跳过；包含2项缓存客户端、6项Easy-Query和8项数据库事务测试 |
| NODE-B04.1 | 建立 Outbox 信封、状态约束与迁移表 | 2026-10-23 | Domain6项模型测试和8项真实数据库事务测试通过；MariaDB已执行Outbox迁移，主键、待投递及聚合索引核对成功 |
| NODE-B04.2 | 完成事件同事务落盘，父节点 NODE-B04 完成 | 2026-10-23 | 5项真实MariaDB Outbox测试通过、零跳过；覆盖同提交/回滚、重复事件、非法JSON回滚及事务外拒写 |
| NODE-B05.1 | 实现带租期与有界等待的 Redis 分布式锁 | 2026-10-23 | 5项真实Redis测试通过、零跳过；覆盖TTL、互斥、过期接管、旧token安全释放、等待超时与迟到租约清理 |
| NODE-B05.2a | 建立版本化缓存契约与有界请求合并核心 | 2026-10-23 | 7项测试通过、0失败/跳过；覆盖空值60秒、TTL抖动边界、并发合并、失败重试、超时不释放加载容量及关闭取消 |
| NODE-B05.2b1 | 实现 Redis 版本存储、失效屏障与跨实例 L1 校验 | 2026-10-23 | 5项真实Redis测试通过、0失败/跳过；覆盖TTL、空值并发保护、大整数版本、旧版本拒写与跨实例失效 |
| NODE-B05.2b2 | 装配版本化缓存门面与跨实例互斥重建 | 2026-10-23 | 12项真实Redis测试通过、0失败/跳过；包含7项门面测试及5项存储回归，验证多实例单次回源、空值复用、超时与并发失效 |
| NODE-B05.3a | 实现提交后缓存更新、失败失效与同事务补偿意图 | 2026-10-23 | 6项真实MariaDB事务测试通过、0失败/跳过；验证独立连接提交可见、回滚无缓存副作用、Redis双重故障仍保留Outbox及参数拒绝 |
| NODE-B05.3b1 | 接入缓存低基数指标、回源耗时与链路日志 | 2026-10-24 | 8项真实Redis门面测试通过、0失败/跳过；核对命中/未命中、回源/错误计数、trace传播、默认日志实际字段及指标标签 |
| NODE-B05.3b2 | 完成锁指标与并发故障回归，父节点 NODE-B05 及 Phase B 完成 | 2026-10-24 | 19项单元测试和46项真实MariaDB/Redis集成测试通过、0失败/跳过；覆盖事务、ORM、Outbox、缓存、补偿、锁竞争/迟到释放及指标日志 |
| NODE-C01.1 | 建立类型化查询条件、分页复用与排序过滤白名单 | 2026-10-24 | query test 5项通过、0失败/跳过；覆盖分页边界、非法字段/方向/操作、类型与区间校验、不可变集合及注入文本保留 |
| NODE-C01.2 | 建立查询处理契约、有界结果与显式上下文 | 2026-10-24 | query test 10项通过、0失败/跳过；新增5项结果数量/不可变上下文测试，另5项QuerySpec回归通过 |
| NODE-C01.3 | 完成查询统一路由、装配与观测，父节点 NODE-C01 完成 | 2026-10-24 | query test 16项通过、0失败/跳过；新增6项路由/上下文/异常/事务边界/Spring装配测试，starter bootJar退出码0 |
| NODE-C02.1 | 建立HTTP请求关联标识与分派生命周期 | 2026-10-24 | interface test 6项通过、0失败/跳过；覆盖合法与非法头、异常恢复、异步及错误再分派和请求隔离；starter bootJar退出码0 |

---

## 3. 待执行节点（按顺序）

下一节点为 **NODE-C02.2**；范围、依赖及后续顺序见 [开发计划](AI可执行开发计划.md) 第 4 节。每节点验收、审查、提交并推送后继续；部署相关节点暂缓。

---

## 4. 活跃阻塞项

当前未登记阻塞项。新增阻塞时记录编号、受影响节点、原因及解除条件；不将旧交接中的编辑器提示自动作为当前阻塞。

---

## 5. 已确认决策与沿革

本轮业务目标已由用户确认为标准企业 ERP，首期范围为通用进销存：物料、客户、供应商、采购、销售与库存。后续 NODE-C03 按此拆分业务闭环，不采用笔记示例。用户进一步确认：采购收货增加库存、销售出库扣减库存、禁止负库存，支持仓库与小数数量；首期不加入财务记账、税务、批次序列号及生产 BOM。

架构约束与优先级见 [CONSTITUTION](CONSTITUTION.md)，阶段冻结决策见 [SDD 执行总览](SDD执行总览.md) 第 3 节。下表保留既有决策沿革，不作为活动任务；DECISION-A16 的“未引入 Lombok”前提已由 A17 替代，现行使用范围见 A18 与 [开发与迭代](development.md)。

| 决策 | 内容 | 记录位置 |
|------|------|----------|
| DECISION-A01 | 根工程收敛为 Gradle 聚合根，并按 `stratum-*` 统一模块命名与注册 | `build.gradle`、`settings.gradle` |
| DECISION-A02 | 将“变更后必须同步更新 CONTEXT 与 SESSION-HANDOFF”固化为工作区级强制规则 | `.github/copilot-instructions.md`、`.github/prompts/stratum-node.prompt.md` |
| DECISION-A03 | starter 模块采用 Spring Boot 4.0.5 启动，默认启用 local profile，并预留 Nacos 配置导入占位 | `build.gradle`、`stratum-starter/build.gradle`、`stratum-starter/src/main/resources/application.yml` |
| DECISION-A04 | 版本由外部统一约定；子模块构建脚本禁止声明版本号（包括插件版本） | `build.gradle`、`stratum-starter/build.gradle` |
| DECISION-A05 | 统一响应模型、错误码结构与 traceId 线程上下文下沉到 common；interface 通过工厂类复用 | `stratum-common/src/main/java/com/patrick/stratum/common/**`、`stratum-interface/src/main/java/com/patrick/stratum/interfaceadapter/response/ApiResponseFactory.java` |
| DECISION-A06 | 全局异常处理放置于 starter，异常映射优先识别 StratumException，统一通过 ErrorCode.messageKey + MessageSource 输出本地化 message，Accept-Language 缺失时默认 zh-CN | `stratum-common/src/main/java/com/patrick/stratum/common/error/StratumException.java`、`stratum-starter/src/main/java/com/patrick/stratum/starter/web/GlobalExceptionHandler.java`、`stratum-starter/src/main/resources/messages*.properties` |
| DECISION-A07 | common 模块冻结 extension/helper/base/enums 四类基础目录与命名约定；字符串、集合、时间、分页、校验能力统一下沉 common，application/interface/query 禁止重复实现同类逻辑 | `stratum-common/src/main/java/com/patrick/stratum/common/**`、`docs/项目结构.md` |
| DECISION-A08 | 网关技术基线调整为 Spring Boot Gateway；负载均衡由项目外组件负责（裸金属 Nginx 或容器编排层） | `docs/网关与负载均衡设计.md`、`docs/技术架构.md`、`docs/容器化与部署.md`、`docs/SDD执行总览.md` |
| DECISION-A09 | AI 执行节点口径同步：NODE-D04 改为 Spring Boot Gateway 配置 + 项目外负载均衡对接约束，移除项目内 upstream 实现要求 | `docs/AI可执行开发计划.md` |
| DECISION-A10 | 仓库新增 repo-local Copilot skills（api-design、backend-patterns、coding-standards、security-review、tdd-workflow、verification-loop），统一绑定 Stratum 规范、模块边界与 Gradle 校验口径 | `.github/skills/**`、`docs/CONTEXT.md`、`docs/entropy/SESSION-HANDOFF.md` |
| DECISION-A11 | repo-local Copilot skills 继续扩展到实现面：新增 query-patterns、outbox-workflow、gateway-routing，分别绑定查询基架、事务一致性与网关职责边界 | `.github/skills/**`、`docs/查询基架设计.md`、`docs/工作单元与事务一致性设计.md`、`docs/网关与负载均衡设计.md` |
| DECISION-A12 | repo-local Copilot commit skill 统一使用中文短标题，默认省略正文，并遵循 GitHub 常见 Conventional Commits 风格生成提交信息 | `.github/skills/commit/references/commit-message.md`、`docs/CONTEXT.md`、`docs/entropy/SESSION-HANDOFF.md` |
| DECISION-A13 | VS Code 源代码管理器“生成提交信息”入口通过工作区设置 `github.copilot.chat.commitMessageGeneration.instructions` 显式绑定 `.github/skills/commit/references/commit-message.md`，不再依赖 skill 自动发现 | `.vscode/settings.json`、`.github/skills/commit/references/commit-message.md`、`docs/CONTEXT.md`、`docs/entropy/SESSION-HANDOFF.md` |
| DECISION-A14 | repo-local Copilot tdd-workflow skill 改为按需启用：仅在用户明确要求 TDD 或高风险逻辑需优先设计测试策略时触发，普通实现与低风险改动不默认启用 | `.github/skills/tdd-workflow/SKILL.md`、`docs/CONTEXT.md`、`docs/entropy/SESSION-HANDOFF.md` |
| DECISION-A15 | common.base 目录细化为 request/result 子包；统一分页请求与分页结果分别下沉为 BasePageRequest、BasePageResult，避免基础请求/结果模型继续堆叠在同一目录 | `stratum-common/src/main/java/com/patrick/stratum/common/base/**`、`stratum-query/src/main/java/com/patrick/stratum/query/reuse/QueryCommonReuseExample.java`、`docs/项目结构.md` |
| DECISION-A16 | AI 写 Java 代码时统一遵循注解与 Lombok 规范：当前未显式引入 Lombok 时禁止直接新增相关注解；`@Builder` 仅限 DTO/Result/测试构造等纯数据承载场景，`@Data` 默认禁用，`@Transactional` 只允许出现在 Application 写用例边界，配置绑定优先 `@ConfigurationProperties`，参数校验优先 `jakarta.validation` + `@Valid`/`@Validated` | `.github/copilot-instructions.md`、`.github/skills/coding-standards/SKILL.md`、`docs/CONTEXT.md`、`docs/entropy/SESSION-HANDOFF.md` |
| DECISION-A17 | 根工程统一接入 Lombok 的 compileOnly/annotationProcessor 基线；允许在 common/interface/query/application/test 的纯数据承载模型按规范使用 `@Builder`，并将 Lombok 生成的 getter/setter/constructor/builder 视为生成代码，不要求逐一补中文方法注释 | `build.gradle`、`.github/copilot-instructions.md`、`.github/skills/coding-standards/SKILL.md`、`stratum-common/src/main/java/com/patrick/stratum/common/base/**`、`stratum-query/src/main/java/com/patrick/stratum/query/reuse/QueryCommonReuseExample.java` |
| DECISION-A18 | Lombok 分层使用改为固定白名单模板：interface/query/application 的纯数据类统一采用 `@Getter` + `@Builder` 模板，Spring 托管组件统一采用 `@RequiredArgsConstructor` 并按需追加 `@Slf4j`，若当前模块无匹配 DTO/Request/Response/Result，则不得为凑风格新增样板类 | `.github/copilot-instructions.md`、`.github/skills/coding-standards/SKILL.md`、`docs/CONTEXT.md`、`docs/entropy/SESSION-HANDOFF.md` |

---

## 6. 文件结构现状

```text
Stratum/
├── AGENTS.md                 # 共享开发指引
├── README.md                 # 项目入口
├── build.gradle              # 聚合根构建
├── settings.gradle           # 注册 9 个子模块
├── gradle/wrapper/           # Gradle 9.4.1
├── .github/                  # Copilot 入口、提示词与按需技能
├── stratum-*/                # 9 个模块，能力现状见开发与迭代
└── docs/
    ├── README.md             # 文档导航
    ├── AGENTS.md             # 文档维护补充指引
    ├── development.md        # 基线、命令与验证方式
    ├── CONSTITUTION.md       # 架构宪法
    ├── CONTEXT.md            # 当前状态与决策沿革
    ├── FORBIDDEN.md          # 禁止项
    ├── GLOSSARY.md           # 术语
    ├── SDD执行总览.md
    ├── Spec清单.md
    ├── AI可执行开发计划.md
    ├── 各专题设计文档
    ├── adr/                  # ADR-001 索引、ADR-002 ORM 决策
    └── entropy/              # 偏移日志与最近交接
```

专题文档入口见 [文档导航](README.md)，源码模块职责见 [项目结构](项目结构.md)。仓储模板位于 starter 的 `src/test/java/com/patrick/stratum/starter/integration/fixture`，仅用于可运行参考；它实现 Domain Repository 契约，显式映射聚合与 ORM 表，不是已交付 ERP 业务仓储。MariaDB 1020 快照冲突和 ORM 乐观锁冲突均映射为 40900，其他 SQL 错误保持原异常。

当前没有 `docker/compose/`；Infrastructure 已有 Flyway 初始版本和 Outbox 表迁移；Outbox 信封/状态/写入端口、持久化适配与 Application 落盘入口已实现并通过同事务测试；Relay 与消息投递仍待 Phase E，application 已有 6 项命令入口测试。

---

## 7. 更新记录

| 日期 | 更新内容 | 来源 |
|------|----------|------|
| 2026-03-31 | 初始化本文件，规范文档建立完成 | 人工 |
| 2026-04-07 | 完成 NODE-A01：初始化 Gradle 多模块骨架并通过基础验证 | AI执行后人工确认 |
| 2026-04-07 | 固化会话收尾规则：每次变更后必须同步更新 CONTEXT 与 SESSION-HANDOFF | AI执行后人工确认 |
| 2026-04-07 | 完成 NODE-A02：建立 starter 启动骨架、profiles 与 Nacos 配置占位，并开放 actuator health 端点 | AI执行后人工确认 |
| 2026-04-07 | 调整构建版本策略：Spring Boot 插件版本上提到根工程统一管理，starter 子模块移除插件版本号 | AI执行后人工确认 |
| 2026-04-08 | 完成 NODE-A03：新增 ApiResponse、统一错误码结构与 TraceIdContext，并在 interface 模块建立复用入口 | AI执行后人工确认 |
| 2026-04-09 | 完成 NODE-A04：新增 StratumException 全局异常基类、全局异常映射与 i18n 消息解析，并在统一响应模型中输出本地化错误消息与 traceId | AI执行后人工确认 |
| 2026-04-09 | 完成 NODE-A05：建立 extension/helper/base/enums 复用基线，下沉字符串、集合、时间、分页、校验最小能力，并补充 application/interface/query 复用示例与复用约束 | AI执行后人工确认 |
| 2026-05-14 | 同步网关与负载均衡技术文档：网关改为 Spring Boot Gateway，负载均衡改为项目外处理（裸金属 Nginx/容器编排层） | AI执行后人工确认 |
| 2026-05-14 | 同步 AI 可执行节点口径：NODE-D04 改为 Spring Boot Gateway + 项目外负载均衡，不再要求项目内 upstream 实现 | AI执行后人工确认 |
| 2026-05-18 | 新增 repo-local Copilot skills：API 设计、后端模式、编码规范、安全审查、TDD、验证闭环，统一接入 Stratum 规范与校验流程 | AI执行后人工确认 |
| 2026-05-18 | 扩展 repo-local Copilot skills：新增查询基架、Outbox 工作流、网关路由技能，对齐实现侧专题文档 | AI执行后人工确认 |
| 2026-05-18 | 收紧 repo-local Copilot commit skill 提交信息规范：统一中文、简短标题、正文默认省略，并对齐 GitHub 常见 Conventional Commits 风格 | AI执行后人工确认 |
| 2026-05-18 | 为源代码管理器提交信息生成入口补充工作区设置绑定，确保 SCM 按钮读取 commitMessageGeneration 规则文件 | AI执行后人工确认 |
| 2026-05-18 | 收窄 repo-local Copilot TDD skill 触发条件，仅在显式 TDD 请求或高风险逻辑需优先设计测试策略时启用 | AI执行后人工确认 |
| 2026-05-18 | 拆分 common.base 请求/结果子包，迁移 BasePageRequest 并新增 BasePageResult，补齐统一分页结果基类与结构文档 | AI执行后人工确认 |
| 2026-05-18 | 收敛 AI Java 注解与 Lombok 使用规范，明确 Builder/Data 适用边界以及校验、事务、配置绑定注解口径 | AI执行后人工确认 |
| 2026-05-18 | 根工程统一接入 Lombok，并将 BasePageRequest、BasePageResult 与 QueryCommonReuseExample 按规范改造为 builder 友好用法 | AI执行后人工确认 |
| 2026-05-18 | 补齐 Lombok 分层白名单固定模板，并确认 application/query/interface 当前主代码无额外纯 DTO/Request/Result 可继续迁移 | AI执行后人工确认 |
| 2026-06-28 | 完成 NODE-B01：建立 Domain 核心抽象（AggregateRoot、Entity、ValueObject、Repository 接口、DomainEvent），domain 模块仅依赖 common，无框架依赖 | AI执行后人工确认 |
| 2026-10-23 | 整理文档入口，新增根目录 AGENTS / README 与开发指引；修正失效路径、规范优先级和过期状态，NODE-B02 仍待开始 | 用户请求，仓库文件核对 |

---

_当前版本：v1.17 | 最后更新：2026-10-23_
