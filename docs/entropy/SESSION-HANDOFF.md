# Stratum 会话交接模板（SESSION-HANDOFF）

> **用途：** 每次 AI 开发会话结束时，必须按本模板输出交接报告，写入本文件（覆盖上次记录）。
> **目的：** 防止上下文断裂，让下一次会话快速恢复准确状态。
> **AI 执行规则：** 会话完成节点或中断时，必须输出交接报告，否则本次会话视为未完成。

---

## 交接报告格式

```markdown
## 交接报告（DATE）

### 本次会话完成
- completedNodes: [NODE-XXX, ...]
- changedFiles:
  - path/to/file1.java（描述）
  - path/to/file2.java（描述）

### 验收结果
- ✅ 模块依赖方向正确
- ✅ Domain 无框架依赖
- ❌ <未通过项描述>

### 未解决项（Unresolved）
- [UNRESOLVED-001] <描述，原因，阻塞情况>

### 下一节点
- nextNode: NODE-XXX
- 前置条件: <是否满足>
- 建议优先读取: CONTEXT.md, NODE-XXX 节

### 发现的规范问题
- [DRIFT-XXX] <描述>（已记录至 entropy/DRIFT-LOG.md）

### 上下文状态
- 当前技术栈状态：无变化 / <有变化时描述>
- CONTEXT.md 已更新：是 / 否（原因）
```

---

## 当前交接报告

_暂无。项目处于规范文档初始化阶段，首个开发节点尚未开始。_

---

## 历史交接归档

| 日期 | 节点 | 状态 | 摘要 |
|------|------|------|------|
| 2026-03-31 | 规范初始化 | 完成 | 建立 CONSTITUTION、CONTEXT、FORBIDDEN、GLOSSARY、ADR、entropy 目录 |

---

_当前版本：v1.0 | 建立日期：2026-03-31_
