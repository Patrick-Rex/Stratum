---
description: "接收一个 nodeId，读取 Stratum 全局入口文档和该节点参考文档，再执行单个节点"
name: "Stratum Node"
argument-hint: "nodeId，例如：NODE-A01"
agent: "agent"
---

将用户提供的参数视为目标 nodeId，并按以下顺序执行：

1. 若未提供 nodeId，停止执行，并仅要求用户补充 nodeId。
2. 读取以下全局入口文档：
	- [CONSTITUTION.md](../../docs/CONSTITUTION.md)
	- [CONTEXT.md](../../docs/CONTEXT.md)
	- [FORBIDDEN.md](../../docs/FORBIDDEN.md)
	- [Spec清单.md](../../docs/Spec清单.md)
	- 按命名需要查阅 [GLOSSARY.md](../../docs/GLOSSARY.md)
3. 读取 [AI可执行开发计划.md](../../docs/AI可执行开发计划.md)，定位对应 nodeId 的节点定义。
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
7. 当 readyToImplement = yes 时，直接继续执行该节点；上述确认信息是执行前自检，不额外要求用户重复授权。
8. 实现时只允许完成该节点“本次只做”范围内的内容，不得进入下一节点。

执行要求：
- 若依赖未满足或存在相关 blockers，停止实现并明确说明原因。
- 执行时必须遵守 [CONSTITUTION.md](../../docs/CONSTITUTION.md)、[CONTEXT.md](../../docs/CONTEXT.md)、[FORBIDDEN.md](../../docs/FORBIDDEN.md)、[Spec清单.md](../../docs/Spec清单.md) 与节点专题规范的约束。
- 若文档之间存在冲突，按以下优先级裁决：CONSTITUTION.md > FORBIDDEN.md > SDD执行总览.md > Spec清单.md > 本节点专题规范 > AI可执行开发计划.md；CONTEXT.md 仅用于确认状态。
- 不得在 readyToImplement = yes 之前直接开始节点实现。
- 若本次变更涉及仓库根目录普通文件，或顶层非点目录下的文件，完成节点前 MUST 同步更新 [CONTEXT.md](../../docs/CONTEXT.md) 与 [entropy/SESSION-HANDOFF.md](../../docs/entropy/SESSION-HANDOFF.md)。
- 若本次仅改动顶层点目录（如 `.github/`、`.vscode/`）下的文件，可不强制同步上述双文档。
- 若存在上述强制同步范围内的文件变更但未完成双文档更新，不得宣告节点完成。
- 完成节点后，输出：completedNode、changedFiles、validations、unresolved、nextNode。
- 通用规则与运行资源清理由 [根目录 AGENTS](../../AGENTS.md) 统一维护。
