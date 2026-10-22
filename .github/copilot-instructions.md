# Stratum Workspace Instructions

- 共享协作、实现、提交与交付规则统一读取 [根目录 AGENTS](../AGENTS.md)，开发入口见 [开发与迭代](../docs/development.md)。
- 规范优先级以 [CONSTITUTION](../docs/CONSTITUTION.md) 第 1 节为准；[CONTEXT](../docs/CONTEXT.md) 只记录当前状态，不覆盖规范。
- `/stratum-start` 用于建立会话上下文，`/stratum-node <NODE-ID>` 用于执行单个节点；按用户任务选择，不为普通文档维护强制要求 nodeId。
- 节点实现前完成入口与节点所需文档加载，核对依赖、范围和验收方式；已具备执行条件时直接继续，不额外请求重复确认。
- 语言与框架细则按需读取相关仓库 skill，先用 `rg` 定位再读取命中段落，不为无关任务加载全部技能。
- 按根目录 AGENTS 的范围同步 [CONTEXT](../docs/CONTEXT.md) 与 [SESSION-HANDOFF](../docs/entropy/SESSION-HANDOFF.md)，交付时给出实际验证结果和文件路径，并清理本次启动的运行资源。
