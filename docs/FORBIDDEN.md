# Stratum 禁止项清单（FORBIDDEN）

> **用途：** 红线清单，AI 在任何节点均不得跨越。违反任一条即构成架构腐烂，必须立即停止并报告。
> **优先级：** 高于所有专题文档，仅次于 CONSTITUTION.md。
> **AI 执行规则：** 完成 CONSTITUTION.md 阅读后，下一步必须读取本文件核查禁止项。

---

## 1. 分层禁止（绝对）

| 编号 | 禁止行为 | 违反后果 |
|------|----------|----------|
| F-001 | Domain 层导入 Spring、Hibernate、EasyQuery、Redis、RabbitMQ 任何包 | 停止实现，报告违规文件 |
| F-002 | Domain 层声明 `@Transactional`、`@Service`、`@Repository` | 停止实现，报告违规文件 |
| F-003 | Infrastructure 层暴露 HTTP 端点（`@RestController`、`@Controller`） | 停止实现，报告违规位置 |
| F-004 | Interface / Query 层直接调用仓储实现类（非接口） | 停止实现，报告调用链 |
| F-005 | Interface / Query 层编写业务规则（if/else 业务判断） | 停止实现，将规则移至 Domain |
| F-006 | Application 层调用 Infrastructure 具体类（必须通过接口） | 停止实现，建议抽取接口 |
| F-007 | Job 层直接操作数据库（必须通过 Application 或 Repository 接口） | 停止实现，报告调用链 |

---

## 2. 技术实现禁止

| 编号 | 禁止行为 | 原因 |
|------|----------|------|
| F-010 | 直接发布 MQ 消息，绕过 Outbox 模式 | 破坏消息-事务一致性 |
| F-011 | 使用 `Thread.sleep()` 实现任何重试或等待逻辑 | 使用 Quartz 重试或 RabbitMQ DLQ 机制 |
| F-012 | 在多副本后台任务中缺少 ShedLock 注解 | 破坏并发保护 |
| F-013 | 使用 `synchronized` 或内存级锁处理分布式并发 | 应使用数据库乐观锁或 Redis 分布式锁 |
| F-014 | 聚合内使用数据库自增 ID 作为业务标识符 | 使用 UUID 或雪花 ID，ID 由 Domain 生成 |
| F-015 | 在 Query 层执行任何写操作（INSERT/UPDATE/DELETE） | Query 层只读 |
| F-016 | 将明文密码存入数据库或日志 | 密码必须哈希（BCrypt），日志脱敏 |
| F-017 | 将 JWT 私钥硬编码在源代码中 | 通过 Nacos 或环境变量注入 |
| F-018 | 跨业务能力直接调用其他聚合的内部方法 | 通过领域事件或 ApplicationService 协调 |

---

## 3. 命名与结构禁止

| 编号 | 禁止行为 | 正确做法 |
|------|----------|----------|
| F-020 | 将 Infrastructure 实现类命名为 `XxxService`（与 ApplicationService 混淆） | 使用 `XxxRepositoryImpl`、`XxxAdapterImpl` |
| F-021 | 在 Common 模块中引入任何业务概念 | Common 只含工具类、枚举、常量 |
| F-022 | 跳过 GLOSSARY.md 自行定义术语（特别是 DAO、Manager、Entity 用法） | 参照 GLOSSARY.md 规范命名 |
| F-023 | 在单个节点中同时修改超过 8 个文件 | 拆分为更细的节点 |

---

## 4. 过程控制禁止

| 编号 | 禁止行为 |
|------|----------|
| F-030 | 在节点未通过验收时进入下一节点实现 |
| F-031 | 不读取 CONSTITUTION.md 直接开始实现 |
| F-032 | 发现规范冲突时自行裁决，不记录 DRIFT-LOG |
| F-033 | 修改 CONSTITUTION.md 或 FORBIDDEN.md 本身（仅人工可改） |
| F-034 | 依赖会话记忆（上一轮对话内容）而非文档推进任务 |
| F-035 | 会话结束时不输出 SESSION-HANDOFF 交接报告 |

---

## 5. 发现违规后的处置流程

```
1. 立即停止当前实现
2. 输出违规描述：{违规编号, 违规文件/位置, 已完成变更文件}
3. 等待人工确认处理方式（修正 / 特例豁免并记录 ADR）
4. 豁免特例必须记录至 adr/ADR-XXX.md 并注明生效范围
```

## 6. 代码注释强制规范（F-040系列）

- F-040: 所有 public 类必须有中文类注释，说明职责与使用场景
- F-041: 所有 public 方法必须有中文注释，包括：参数说明、返回值、抛出异常、使用示例
- F-042: Domain 层类注释必须包含聚合根 / 实体 / 值对象身份标记
- F-043: 禁止出现无意义注释（"这是一个类"）或过时注释
- F-044: TODO 注释必须包含 JIRA 票号，否则禁止提交

---

_当前版本：v1.0 | 建立日期：2026-03-31_
