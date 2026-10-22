# Stratum 文档导航

本目录同时保存设计规范和开发记录。设计规范中的 MUST、SHOULD、MAY 分别表示必须、建议和可选要求；功能是否已实现，以 [CONTEXT](CONTEXT.md)、源码和本次验证结果为准。

## 开始工作

| 文档 | 职责 |
|------|------|
| [项目介绍](../README.md) | 项目定位与本地开发入口 |
| [根目录 AGENTS](../AGENTS.md) | 通用协作、实现、提交和交付规则 |
| [开发与迭代](development.md) | 阅读顺序、当前工程基线、命令与验证方式 |
| [CONTEXT](CONTEXT.md) | 当前节点、完成记录及已确认决策 |
| [SESSION-HANDOFF](entropy/SESSION-HANDOFF.md) | 最近一次会话交接，不承载历史任务清单 |

## 规范与实施

| 文档 | 职责 |
|------|------|
| [CONSTITUTION](CONSTITUTION.md) | 架构约束和规范优先级 |
| [FORBIDDEN](FORBIDDEN.md) | 分层、实现及过程禁止项 |
| [SDD 执行总览](SDD执行总览.md) | 阶段目标、变更控制和完整交付标准 |
| [Spec 清单](Spec清单.md) | SPEC-001～SPEC-013 的权威文档及能力映射 |
| [AI 可执行开发计划](AI可执行开发计划.md) | NODE-A01～NODE-F04 的目标、依赖和验收条件 |
| [术语表](GLOSSARY.md) | 领域、模块、接口和基础设施命名 |

按任务通过 Spec 清单读取对应专题，无需每次通读全部设计：

- 架构与模块：[技术架构](技术架构.md)、[项目结构](项目结构.md)。
- 数据与一致性：[基础设施配置](基础设施配置.md)、[查询基架设计](查询基架设计.md)、[工作单元与事务一致性设计](工作单元与事务一致性设计.md)。
- 接口与安全：[API 设计规范](API设计规范.md)、[安全认证设计](安全认证设计.md)、[网关与负载均衡设计](网关与负载均衡设计.md)。
- 异步与横切：[消息总线设计](消息总线设计.md)、[后台任务设计](后台任务设计.md)、[横切关注点与 AOP 设计](横切关注点与AOP设计.md)。
- 运维：[可观测性设计](可观测性设计.md)、[容器化与部署](容器化与部署.md)。

## 决策与协作工具

- [ADR 索引](adr/ADR-001.md)：已落盘架构决策与新增记录模板。
- [偏移日志](entropy/DRIFT-LOG.md)：规范冲突、实现偏移及处置结果。
- [Copilot 入口](../.github/copilot-instructions.md)：引用共享开发规则。
- [会话初始化提示词](../.github/prompts/stratum-start.prompt.md)、[节点执行提示词](../.github/prompts/stratum-node.prompt.md)：按当前状态接续节点。
- [仓库技能](../.github/skills)：仅按任务加载；现有技能不代表对应框架已接入。
