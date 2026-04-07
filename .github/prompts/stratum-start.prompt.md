---
description: "初始化 Stratum 会话，读取 5 个全局入口文档并输出 currentPhase、lastCompletedNode、blockers、readyForNode"
name: "Stratum Start"
agent: "agent"
---

按以下顺序读取全局入口文档：
- [CONSTITUTION.md](../../CONSTITUTION.md)
- [CONTEXT.md](../../CONTEXT.md)
- [FORBIDDEN.md](../../FORBIDDEN.md)
- [GLOSSARY.md](../../GLOSSARY.md)
- [Spec清单.md](../../Spec清单.md)

这是全局上下文建立步骤，不进入任何 NODE 实现，不输出计划、代码或修改建议。

若文档之间存在冲突，按以下优先级裁决：
- CONSTITUTION.md
- CONTEXT.md
- FORBIDDEN.md
- Spec清单.md

只输出以下 4 个字段，不要附加解释、计划、实现细节、代码或额外说明：
- currentPhase
- lastCompletedNode
- blockers
- readyForNode

补充约束：
- blockers 为空时输出 `无`。
- readyForNode 必须基于 [CONTEXT.md](../../CONTEXT.md) 的已完成节点、待执行节点与阻塞项综合判断。