---
name: coding-standards
description: Stratum 编码规范技能。用于约束命名、注释、复用、构建脚本与代码可维护性，作为本仓库的通用质量下限。
---

# Stratum 编码规范技能

## 何时启用

- 新增模块、类、方法、配置或构建脚本
- 重构已有实现
- 评审命名、注释、重复逻辑与可读性
- 补充 common 复用能力

## 先读这些规范

- docs/CONSTITUTION.md
- docs/FORBIDDEN.md
- docs/项目结构.md
- docs/CONTEXT.md

## Stratum 通用规则

- 所有回复、注释与说明统一使用中文。
- 所有 public 类必须有中文类注释；所有 public 方法必须有中文方法注释，至少包含用途、关键参数、返回值、异常条件与必要示例。
- 优先复用 `stratum-common` 的 `extension/helper/base/enums`，禁止在 `application/interface/query` 重复实现同类基础逻辑。
- 新增 Infrastructure 实现类使用 `XxxRepositoryImpl` 或 `XxxAdapterImpl`，避免命名为 `XxxService`。
- 子模块 `build.gradle` 不声明版本号，统一由根工程管理。
- 禁止无意义注释、单字母变量名、魔法数字、过深嵌套与超长方法。
- 先写清晰实现，再考虑抽象；没有明确复用场景时不要过度泛化。

## 命名与结构建议

- 聚合、实体、值对象、仓储接口放在 Domain 对应包下。
- `Command/Query/Result/Handler` 命名应直接体现业务动作。
- `DTO/Request/Response/Spec/Helper/Extensions` 使用职责型后缀。
- 常量应提取为语义化常量名，避免裸值散落。

## 重构检查点

- 是否能用早返回减少嵌套？
- 是否把基础通用逻辑沉淀到 common？
- 是否把业务判断留在 Domain，把编排留在 Application？
- 是否仅为当前真实需求建模，而不是为未来猜测设计？

## 常见反例

- 为一次性逻辑提前抽象三层接口。
- 在多个模块复制相同的字符串、分页、校验逻辑。
- 注释解释代码表面动作，而不解释设计原因。
- 构建脚本在子模块写死插件或依赖版本。

## 验证建议

- 变更后先运行受影响模块的 `gradlew :模块:build`
- 抽样检查 public 类和 public 方法是否补齐中文注释
- 抽样检查是否遗漏 `CONTEXT` 与 `SESSION-HANDOFF` 更新