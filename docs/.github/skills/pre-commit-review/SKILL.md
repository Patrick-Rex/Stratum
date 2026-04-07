---
name: pre-commit-review
description: 提交前代码审查规范; 即将执行提交前, 审查代码时使用
---

## 参数说明

本技能接受一个可选参数：

- **参数**: `output_file` - 输出文件名（可选）
- **用途**: 指定审查结果的保存位置
- **行为**:
  - 如果提供文件名：将审查结果保存到指定文件
  - 如果参数为空：直接将审查结果输出到命令行环境

### 使用示例

```bash
# 保存审查结果到文件
/pre-commit-review review-result.md

# 直接输出到命令行
/pre-commit-review
```

## 审查执行流程

### 1. 获取变更范围

```bash
# 查看当前状态
git status -sb

# 查看变更统计
git diff --stat

# 查看详细变更
git diff
```

**特殊情况处理**：

- **无变更**：询问用户是否检查已暂存内容或特定提交范围
- **大规模变更（>500 行）**：按模块/功能区分批次审查
- **混合变更**：按功能逻辑分组，而非仅按文件顺序

### 2. 执行审查

按照以下维度进行系统性审查：

#### 架构与 SOLID 原则
- SRP: 单一职责原则
- OCP: 开闭原则
- LSP: 里氏替换原则
- ISP: 接口隔离原则
- DIP: 依赖倒置原则

#### 安全风险
- 注入攻击（SQL/NoSQL/命令注入）
- 认证授权缺陷
- 敏感信息泄露
- 并发问题

#### 代码质量
- 错误处理完整性
- 性能问题（N+1 查询等）
- 边界条件处理
- 可移除代码识别

#### 文档关联
- docs/ 目录文件变更是否更新索引
- 重大功能变更是否更新设计文档
- **注意**：`$` 开头的目录属于外部项目文档目录，内部文件为只读文件，不审查

### 3. 输出审查结果

根据参数决定输出方式：
- **有参数**：保存到指定文件
- **无参数**：输出到命令行

**输出约束**：
- **无明显问题**：只能输出 `审查完成,未发现问题`，不允许其他内容
- **存在问题**：按标准格式输出审查报告

## 严重程度分级

| 等级 | 名称 | 说明 | 处理要求 |
|------|------|------|----------|
| **P0** | 严重 | 安全漏洞、数据丢失风险、正确性 bug | **必须阻塞合并** |
| **P1** | 高 | 逻辑错误、重大架构违背、性能回退 | 合并前应修复 |
| **P2** | 中 | 代码异味、可维护性问题、轻微违背原则 | 本次或后续迭代修复 |
| **P3** | 低 | 风格问题、命名建议、细微优化 | 可选改进 |

## 参考文档

详细的审查清单和规范请参考：

| 描述 | 位置 |
|------|------|
| 审查总结示例 | [references/example-output.md](references/example-output.md) |
| 精简版示例 | [references/example-output-pass.md](references/example-output-pass.md) |
| SOLID 原则检查项 | [references/solid-checklist.md](references/solid-checklist.md) |
| 安全风险清单 | [references/security-checklist.md](references/security-checklist.md) |
| 代码质量清单 | [references/code-quality-checklist.md](references/code-quality-checklist.md) |
| 冗余代码删除计划 | [references/removal-plan.md](references/removal-plan.md) |
