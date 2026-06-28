# Stratum 上下文快照（CONTEXT）

> **用途：** 为每次 AI 会话提供项目当前状态的快照，防止上下文从零构建带来的偏移。
> **维护者：** 每个开发节点完成后，由人工（或在 AI 监督下）更新本文件。
> **AI 读取时机：** CONSTITUTION.md 之后、FORBIDDEN.md 之前（执行五步协议的 STEP 2）。

---

## 1. 当前阶段

```
阶段：Phase B（写模型骨架）
当前节点：NODE-B02（待开始）
上次会话日期：2026-06-28
项目状态：NODE-B01 已完成，Domain 核心抽象已建立（AggregateRoot、Entity、ValueObject、Repository、DomainEvent），domain 模块仅依赖 common，无框架依赖；Phase A 全部节点已完成
```

---

## 2. 已完成节点

| 节点 | 描述 | 完成日期 | 验收状态 |
|------|------|----------|----------|
| NODE-A01 | 初始化 Gradle 多模块骨架 | 2026-04-07 | 通过（`gradlew projects`、依赖方向检查） |
| NODE-A02 | 建立 starter 启动骨架与基础配置加载 | 2026-04-07 | 通过（`gradlew build`、`bootRun`、`/actuator/health`） |
| NODE-A03 | 建立公共基础模块（ApiResponse、错误码、traceId 工具） | 2026-04-08 | 通过（`:stratum-common:build`、`:stratum-interface:build`） |
| NODE-A04 | 建立统一异常处理与多语言消息基础设施 | 2026-04-09 | 通过（`:stratum-common:build`、`:stratum-starter:build`） |
| NODE-A05 | 建立基础扩展类与帮助类复用基线 | 2026-04-09 | 通过（`:stratum-common:build`、`:stratum-application:build`、`:stratum-query:build`、`:stratum-interface:build`） |
| NODE-B01 | 建立 Domain 核心抽象 | 2026-06-28 | 通过（`:stratum-domain:build`、依赖方向与源码检查无框架依赖） |

---

## 3. 待执行节点（按顺序）

| 节点 | 描述 | 依赖 |
|------|------|------|
| NODE-B02 | 建立 Application 写用例基架 | NODE-B01, NODE-A03, NODE-A05 |
| … | 见 AI可执行开发计划.md | … |

---

## 4. 活跃阻塞项

> 无阻塞项。如有，按如下格式添加：

```
- [BLOCKED-001] NODE-XXX 依赖 YYY 未就绪，等待：<原因>
```

---

## 5. 当前已知技术决策（最新状态）

所有已冻结决策见 `SDD执行总览.md` 第 3 节。
本节仅记录本 Sprint/阶段的新增决策：

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

```
Stratum/
├── build.gradle         ✅ 已更新为聚合根构建
├── settings.gradle      ✅ 已注册 9 个子模块
├── CONSTITUTION.md       ✅ 已建立（架构宪法）
├── CONTEXT.md            ✅ 本文件（上下文快照）
├── FORBIDDEN.md          ✅ 已建立（禁止项清单）
├── GLOSSARY.md           ✅ 已建立（术语表）
├── SDD执行总览.md         ✅ 已建立
├── Spec清单.md            ✅ 已建立
├── AI可执行开发计划.md    ✅ 已建立
├── 技术架构.md            ✅ 已建立
├── 项目结构.md            ✅ 已建立
├── 各专题设计文档         ✅ 已建立（SPEC-001 ~ SPEC-013）
├── stratum-gateway/      ✅ 已建立（模块骨架）
├── stratum-interface/    ✅ 已建立（模块骨架）
├── stratum-query/        ✅ 已建立（模块骨架）
├── stratum-application/  ✅ 已建立（模块骨架）
├── stratum-domain/       ✅ 已建立（模块骨架）
├── stratum-infrastructure/ ✅ 已建立（模块骨架）
├── stratum-job/          ✅ 已建立（模块骨架）
├── stratum-common/       ✅ 已建立（模块骨架）
├── stratum-starter/      ✅ 已建立（启动类、profiles/Nacos 配置占位、actuator 基础端点）
├── adr/
│   └── （待添加）
└── entropy/
    ├── DRIFT-LOG.md      ✅ 已建立
    └── SESSION-HANDOFF.md ✅ 模板已建立
```

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

---

_当前版本：v1.16 | 最后更新：2026-06-28_
