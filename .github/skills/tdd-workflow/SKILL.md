---
name: tdd-workflow
description: Stratum 测试驱动开发技能。仅在用户明确要求采用 TDD、先测后写、补测试闭环，或处理事务一致性、幂等、安全等高风险逻辑且需要优先设计测试策略时启用；普通实现、文档调整、低风险配置修改不要默认启用。
---

# Stratum 测试驱动开发技能

## 何时启用

- 用户明确要求采用 TDD、先写失败测试或先补测试再实现
- 需要为事务一致性、幂等、安全、权限、Outbox、Job 等高风险逻辑先设计测试策略
- 需要收敛一个明确的红绿重构测试闭环，而不是直接实现

## 不默认启用

- 普通功能实现或常规缺陷修复
- 仅改文档、提示词、配置、脚本或低风险样式调整
- 用户只要求直接实现，且未要求以测试先行驱动开发

## 先读这些规范

- docs/CONSTITUTION.md
- docs/Spec清单.md
- 与本次改动对应的专题设计文档

## TDD 步骤

1. 用中文写出场景，优先采用 `Given / When / Then`。
2. 先选最小测试层级：Domain 先单元测试，Application 先编排测试，Interface/Query 先 slice 或契约测试，Infrastructure 再做集成测试。
3. 先写失败测试，明确 happy path、错误路径、边界条件。
4. 仅实现让当前测试通过的最小代码。
5. 立即重跑同一组窄范围测试。
6. 重构命名、重复逻辑与抽象后，再次运行同一组测试。
7. 最后补一次相邻模块 build 或 test，避免只在单点通过。

## Stratum 测试落点建议

- `stratum-domain`：聚合规则、值对象、不变量、领域事件
- `stratum-application`：CommandHandler、事务边界、Outbox 写入编排
- `stratum-interface`：Controller 契约、参数校验、错误映射、版本头
- `stratum-query`：QueryGateway、QueryHandler、分页排序白名单、数据权限过滤
- `stratum-infrastructure`：仓储实现、缓存、分布式锁、MQ/Outbox 集成
- `stratum-job`：调度入口、幂等、ShedLock 配置与失败重试

## 必测场景

- 成功路径
- 参数或协议失败
- 权限或数据范围失败
- 边界值与空值
- 幂等、重复提交或重复消费
- 事务回滚与补偿触发条件

## 常用命令

- `gradlew :stratum-domain:test`
- `gradlew :stratum-application:test`
- `gradlew :stratum-interface:test`
- `gradlew :stratum-query:test`
- `gradlew :stratum-infrastructure:test`
- `gradlew :stratum-job:test`

## 常见反例

- 先写完整实现，再补“证明它能跑”的测试。
- 只测 happy path，不测错误码、回滚、权限与边界。
- 一上来跑全仓 `gradlew test`，却没有先定位最小失败面。
- 测试依赖前一个测试制造的数据，导致顺序耦合。