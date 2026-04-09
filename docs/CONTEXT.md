# Stratum 上下文快照（CONTEXT）

> **用途：** 为每次 AI 会话提供项目当前状态的快照，防止上下文从零构建带来的偏移。
> **维护者：** 每个开发节点完成后，由人工（或在 AI 监督下）更新本文件。
> **AI 读取时机：** CONSTITUTION.md 之后、FORBIDDEN.md 之前（执行五步协议的 STEP 2）。

---

## 1. 当前阶段

```
阶段：Phase A（基础骨架）
当前节点：NODE-A04（待开始）
上次会话日期：2026-04-08
项目状态：NODE-A03 已完成，公共响应对象、错误码与 traceId 上下文工具已建立
```

---

## 2. 已完成节点

| 节点 | 描述 | 完成日期 | 验收状态 |
|------|------|----------|----------|
| NODE-A01 | 初始化 Gradle 多模块骨架 | 2026-04-07 | 通过（`gradlew projects`、依赖方向检查） |
| NODE-A02 | 建立 starter 启动骨架与基础配置加载 | 2026-04-07 | 通过（`gradlew build`、`bootRun`、`/actuator/health`） |
| NODE-A03 | 建立公共基础模块（ApiResponse、错误码、traceId 工具） | 2026-04-08 | 通过（`:stratum-common:build`、`:stratum-interface:build`） |

---

## 3. 待执行节点（按顺序）

| 节点 | 描述 | 依赖 |
|------|------|------|
| NODE-A04 | 建立统一异常处理与多语言消息基础设施 | NODE-A03 |
| NODE-B01 | 安全认证基础设施（Security + JWT） | NODE-A02, NODE-A03 |
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

---

_当前版本：v1.3 | 最后更新：2026-04-08_
