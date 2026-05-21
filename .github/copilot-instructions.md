# Stratum Workspace Instructions

- 本工作区采用“全局入口 + 节点二次加载”协作模式。
- 全局裁决优先级 MUST 为：[CONSTITUTION.md](../docs/CONSTITUTION.md) > [CONTEXT.md](../docs/CONTEXT.md) > [FORBIDDEN.md](../docs/FORBIDDEN.md) > [Spec清单.md](../docs/Spec清单.md) > 节点专题规范 > 其他参考文档。
- 团队协作时，优先使用共享 prompts：`/stratum-start` 用于建立会话上下文，`/stratum-node <NODE-ID>` 用于执行单个节点。
- 除非用户明确要求直接开始普通问答，否则执行 Stratum 节点任务前 MUST 先完成入口确认或节点二次加载确认。
- 入口文档保持轻量：仅保留全局约束与流程，不承载语言/框架细则。
- 语言与框架规则 MUST 按需加载对应 skill（例如 Java 编码时加载 `.github/skills/coding-standards/SKILL.md`），非相关任务不得强制加载，避免无效 token 消耗。
- 修改任何代码时，仍需满足仓库注释与分层约束；具体实现细则以命中 skill 为准。
- 推荐按需加载流程：先用 `rg` 定位目标规则，再读取命中段落，不整篇通读。
- AI 在每次发生以下任一类文件变更后，MUST 同步更新 [docs/CONTEXT.md](../docs/CONTEXT.md) 与 [docs/entropy/SESSION-HANDOFF.md](../docs/entropy/SESSION-HANDOFF.md)：仓库根目录普通文件，或顶层非点目录（如 `docs/`、`stratum-application/`）下的文件。
- 若本次会话仅改动顶层点目录（如 `.github/`、`.vscode/`）下的文件，则可不强制同步上述双文档。
- 若本次会话存在上述强制同步范围内的文件变更但未完成双文档更新，则本次执行视为未完成，不得结束交付。

## 会话收尾最小清单（MUST）

- 触发强制同步条件时，更新 [docs/CONTEXT.md](../docs/CONTEXT.md)：当前节点、已完成节点、待执行节点、关键决策、更新时间。
- 触发强制同步条件时，覆盖写入 [docs/entropy/SESSION-HANDOFF.md](../docs/entropy/SESSION-HANDOFF.md) 的“当前交接报告”段落，记录 completedNodes、changedFiles、validation、unresolved、nextNode。
- 触发强制同步条件时，最终回复中必须明确声明：CONTEXT 与 SESSION-HANDOFF 已更新，并给出对应文件路径。