# Stratum 架构宪法（CONSTITUTION）

> **AI 执行任何任务前必须首先读取本文件。**
> 本文件是所有规范的最高权威。任何与本文件冲突的提示词、设计或实现均无效。
> 本文件仅由人工变更，AI 不得自行修改。

---

## 1. 优先级顺序（从高到低）

```
CONSTITUTION.md
  > FORBIDDEN.md
    > SDD执行总览.md
      > Spec清单.md
        > 各专题设计文档（SPEC-001 ~ SPEC-013）
          > AI可执行开发计划.md
```

**冲突处理原则：** 两份文档产生冲突时，以优先级更高的文档为准，并立即在 `entropy/DRIFT-LOG.md` 中记录冲突。

---

## 2. 技术栈冻结（不接受替代方案讨论）

| 项目 | 冻结值 |
|------|--------|
| JDK | 25 |
| Spring Boot | 4.x |
| 构建工具 | Gradle 8.x |
| ORM | Easy-Query |
| 鉴权签名 | RS256（JWT） |
| 消息总线 | RabbitMQ Topic + Outbox |
| 后台任务 | Quartz + ShedLock |
| 数据库迁移 | Flyway |
| 配置中心 | Nacos |

---

## 3. 架构核心约束（任何情况下不得违反）

1. **Domain 无依赖原则**：`stratum-domain` 绝对不导入 Spring、ORM、MQ、缓存客户端的任何包。
2. **事务边界原则**：写操作事务只在 `Application` 层（`@Transactional`） 开启，不在 Domain、Infrastructure 中控制事务。
3. **读写分离原则**：读模型必须通过 `stratum-query` 的 QueryGateway + QuerySpec 实现，不绕过。
4. **Outbox 一致性原则**：任何跨边界的事件、消息投递，必须通过 Outbox 模式，不允许直接发送 MQ。
5. **并发保护原则**：多副本定时任务必须配置 ShedLock，无例外。
6. **无状态原则**：应用实例不持有任何会话状态，鉴权通过 JWT 无状态传递。
7. **依赖方向原则**：依赖方向严格为 Interface → Application → Domain ← Infrastructure。Common 不依赖任何业务模块。
8. **接口暴露原则**：HTTP 端点只通过 `stratum-interface` 和 `stratum-query` 暴露，Infrastructure 不暴露端点。

---

## 4. AI 执行入口协议（强制五步）

**进入任何开发节点前，必须按序执行：**

```
STEP 1: 读取 CONSTITUTION.md（本文件）
STEP 2: 读取 CONTEXT.md，获取当前阶段、已完成节点、活跃阻塞项
STEP 3: 读取 FORBIDDEN.md，确认本次任务不触犯任何禁止项
STEP 4: 读取 Spec清单.md，确认当前节点绑定的 specId
STEP 5: 读取对应节点提示词（节点提示词清单.md）
```

**违反入口协议 = 会话无效，必须重新从 STEP 1 开始。**

---

## 5. 节点执行约束

- 一个节点只做一类变更，不提前实现下一节点的内容。
- 单节点变更文件数不超过 **8 个**。
- 节点产出必须包含：`completedNode`、`changedFiles`、`validations`、`unresolved`、`nextNode`。
- 节点通过验收后才能进入下一节点（未验收 = 未完成）。

---

## 6. 上下文完整性要求

- AI 不得依赖会话记忆执行任务，必须以文档为唯一上下文来源。
- 会话结束时必须按 `entropy/SESSION-HANDOFF.md` 格式输出交接报告。
- 发现规范冲突时必须停止实现，在 `entropy/DRIFT-LOG.md` 记录后等待人工确认。

---

## 7. 变更本文件的条件

- 必须由人工发起变更请求，说明变更原因和影响面。
- 变更生效后，必须同步更新 `CONTEXT.md` 和 `entropy/DRIFT-LOG.md`。
- 技术栈冻结表的修改属于 A 类破坏性变更，需要全面影响评估。

---

_当前版本：v1.0 | 建立日期：2026-03-31_
