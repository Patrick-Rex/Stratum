---
description: "接收一个 nodeId，读取 Stratum 全局入口文档和该节点参考文档，再执行单个节点"
name: "Stratum Node"
argument-hint: "nodeId，例如：NODE-A01"
agent: "agent"
---

将用户提供的参数视为目标 nodeId，并按以下顺序执行：

1. 若未提供 nodeId，停止执行，并仅要求用户补充 nodeId。
2. 读取以下全局入口文档：
	- [CONSTITUTION.md](../../CONSTITUTION.md)
	- [CONTEXT.md](../../CONTEXT.md)
	- [FORBIDDEN.md](../../FORBIDDEN.md)
	- [GLOSSARY.md](../../GLOSSARY.md)
	- [Spec清单.md](../../Spec清单.md)
3. 读取 [AI可执行开发计划.md](../../AI可执行开发计划.md)，定位对应 nodeId 的节点定义。
4. 根据该节点中的“参考文档”执行节点级二次加载，至少覆盖：
	- AI可执行开发计划.md
	- Spec清单.md
	- 与本节点直接相关的专题规范正文
5. 使用 inputSpecs 对照 Spec清单.md，给出每个 spec 的文档落点。
6. 在开始实现前，先输出以下确认信息：
	- currentPhase
	- lastCompletedNode
	- blockers
	- readyForNode
	- loadedDocs
	- specCoverage
	- conflicts
	- missingDocs
	- readyToImplement
7. 只有当 readyToImplement = yes 时，才继续执行该节点。
8. 实现时只允许完成该节点“本次只做”范围内的内容，不得进入下一节点。

执行要求：
- 若依赖未满足或存在相关 blockers，停止实现并明确说明原因。
- 执行时必须遵守 [CONSTITUTION.md](../../CONSTITUTION.md)、[CONTEXT.md](../../CONTEXT.md)、[FORBIDDEN.md](../../FORBIDDEN.md)、[Spec清单.md](../../Spec清单.md) 与节点专题规范的约束。
- 若文档之间存在冲突，按以下优先级裁决：CONSTITUTION.md > CONTEXT.md > FORBIDDEN.md > Spec清单.md > 本节点专题规范 > 其他参考文档。
- 不得在 readyToImplement = yes 之前直接开始节点实现。
- 只要产生任何文件变更，完成节点前 MUST 同步更新 [CONTEXT.md](../../CONTEXT.md) 与 [entropy/SESSION-HANDOFF.md](../../entropy/SESSION-HANDOFF.md)。
- 若未完成上述双文档更新，不得宣告节点完成。
- 完成节点后，输出：completedNode、changedFiles、validations、unresolved、nextNode。