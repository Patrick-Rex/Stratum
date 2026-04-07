# Stratum Workspace Instructions

- 本工作区采用“全局入口 + 节点二次加载”协作模式。
- 全局裁决优先级 MUST 为：[CONSTITUTION.md](../CONSTITUTION.md) > [CONTEXT.md](../CONTEXT.md) > [FORBIDDEN.md](../FORBIDDEN.md) > [Spec清单.md](../Spec清单.md) > 节点专题规范 > 其他参考文档。
- 团队协作时，优先使用共享 prompts：`/stratum-start` 用于建立会话上下文，`/stratum-node <NODE-ID>` 用于执行单个节点。
- 除非用户明确要求直接开始普通问答，否则执行 Stratum 节点任务前 MUST 先完成入口确认或节点二次加载确认。
- AI 修改任何代码时，所有新增或修改的类与方法 MUST 增加标准中文注释（类注释说明职责，方法注释说明用途、关键参数、返回值与异常条件）。
- AI 在每次代码、构建脚本或配置文件发生变更后，MUST 同步更新 [docs/CONTEXT.md](../docs/CONTEXT.md) 与 [docs/entropy/SESSION-HANDOFF.md](../docs/entropy/SESSION-HANDOFF.md)。
- 若本次会话存在任何文件变更但未完成上述双文档更新，则本次执行视为未完成，不得结束交付。

## 会话收尾最小清单（MUST）

- 更新 [docs/CONTEXT.md](../docs/CONTEXT.md)：当前节点、已完成节点、待执行节点、关键决策、更新时间。
- 覆盖写入 [docs/entropy/SESSION-HANDOFF.md](../docs/entropy/SESSION-HANDOFF.md) 的“当前交接报告”段落，记录 completedNodes、changedFiles、validation、unresolved、nextNode。
- 最终回复中必须明确声明：CONTEXT 与 SESSION-HANDOFF 已更新，并给出对应文件路径。