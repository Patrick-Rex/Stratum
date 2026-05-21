---
name: coding-standards
description: Stratum 编码规范技能。用于约束命名、注释、复用、构建脚本与代码可维护性，作为本仓库的通用质量下限。
---

# Stratum 编码规范技能

## 何时启用

- 新增或重构 Java/Kotlin 代码、构建脚本、配置。
- 评审命名、注释、复用、可维护性。
- 设计跨层协作边界（Domain/Application/Interface/Query/Infrastructure）。

## 按需加载（必须）

- 禁止整篇通读；先检索后读取，最小化上下文。
- 优先使用 rg 选择性读取：
  - 定位标题：`rg -n "^## " .github/skills/coding-standards/SKILL.md`
  - 定位主题：`rg -n "Lombok|注释|校验|命名|JDK|build.gradle" .github/skills/coding-standards/SKILL.md`
  - 命中后仅读取相关行段。
- 任务完成前仅补读“当前改动直接相关”的规则。

## 先读优先级

- docs/CONSTITUTION.md
- docs/CONTEXT.md
- docs/FORBIDDEN.md
- docs/Spec清单.md
- 再读本技能命中段落。

## JDK 25+ 基线

- 默认目标为 JDK 25+，优先使用标准库与已稳定语言特性。
- 预览特性仅在“有明确收益 + 已在构建与运行环境显式启用”时使用，并在代码注释与提交说明中标注。
- 避免引入与 JDK 25+ 冲突或重复能力的第三方工具库。

## 语言与框架规则（JDK 25+，MUST）

- 全部说明、注释、文档统一中文。
- 所有 public 类必须有中文类注释；所有手写 public 方法必须有中文方法注释（用途、关键参数、返回值、异常条件）。
- Java 语言层：优先不可变设计、早返回、语义化命名，禁止魔法数字、深层嵌套、超长方法。
- 复用层：优先复用 stratum-common 的 extension/helper/base/enums，禁止在 application/interface/query 重复造基础轮子。
- 构建层：子模块 build.gradle 不声明版本号，统一由根工程管理。
- Lombok 层：禁止 @Data；按需使用 @Getter/@Setter/@RequiredArgsConstructor/@Builder/@Slf4j。
- Spring 依赖注入：Spring 组件使用 final 字段 + 构造器注入，禁止字段注入。
- Spring 配置绑定：优先 @ConfigurationProperties，避免同一配置组扩散使用 @Value。
- Spring 校验：输入校验优先 jakarta.validation + @Valid/@Validated，避免重复手写基础校验。
- Spring 事务：@Transactional 仅允许放在 Application 写用例边界；避免 self-invocation 导致事务失效。

## Lombok 白名单（最小化）

- Interface/Query/Application 的纯数据模型：可用 @Getter + @Builder。
- Spring 托管组件（controller/handler/service/factory）：可用 @RequiredArgsConstructor；仅在需要日志时加 @Slf4j。
- Domain：默认禁用 @Builder、@RequiredArgsConstructor、@Slf4j；优先显式构造/工厂保持不变量。
- Common：仅纯数据承载模型可用 @Builder。

## 命名与实现约束

- Infrastructure 实现命名为 XxxRepositoryImpl 或 XxxAdapterImpl，避免 XxxService。
- 命名体现业务动作：Command/Query/Result/Handler/Spec。
- 禁止单字母变量、魔法数字、过深嵌套、超长方法、无意义注释。
- 先实现清晰，再抽象；无复用证据不提前泛化。

## 官方文档加载策略（问题驱动）

- 默认不加载外部社区规范，不再依赖 Alibaba/Anthropic/Obra 链接。
- 仅当出现以下情况时才加载官方文档：规则冲突、实现异常、版本兼容问题、框架行为不确定。
- Java 问题只查官方文档：
  - JDK 25 文档入口：https://docs.oracle.com/en/java/javase/25/
  - Java SE 规范入口：https://docs.oracle.com/javase/specs/
- Spring 问题只查官方文档：
  - Spring Boot Reference：https://docs.spring.io/spring-boot/reference/
  - Spring Framework Reference：https://docs.spring.io/spring-framework/reference/
- 查阅原则：先本地 MUST 规则，后官方文档定点补证；命中问题后只读取相关章节。

## 快速验证

- 运行受影响模块构建：`gradlew :模块:build`
- 抽样检查 public 类/方法中文注释是否齐全。
- 若改动触发仓库规则，确认 docs/CONTEXT.md 与 docs/entropy/SESSION-HANDOFF.md 已同步。