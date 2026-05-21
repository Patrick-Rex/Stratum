---
name: verification-loop
description: Stratum 变更验证闭环技能。用于在提交前对受影响模块、架构边界、文档同步与安全基线做一次可执行检查。
---

# Stratum 验证闭环技能

## 何时启用

- 完成一个功能或缺陷修复后
- 提交 PR 或准备 `git commit` 前
- 对分层边界、接口契约、安全链路有改动时
- 长会话中每完成一个可验证子目标后

## 验证阶段

### 阶段 1：窄范围构建

- 只构建受影响模块，避免一开始跑全仓。
- 示例：
  - `gradlew :stratum-common:build`
  - `gradlew :stratum-domain:build`
  - `gradlew :stratum-interface:build :stratum-query:build`

### 阶段 2：窄范围测试

- 先跑最能否定当前假设的测试，再扩大范围。
- 示例：
  - `gradlew :stratum-application:test`
  - `gradlew :stratum-starter:test`

### 阶段 3：架构红线检查

- Domain 不得出现 Spring、EasyQuery、Redis、RabbitMQ 导入或注解。
- Query 不得包含写操作。
- Job 不得直接操作数据库。
- 如需文本检查，可用：
  - `rg "@Transactional|@Service|@Repository|EasyQuery|Redis|RabbitMQ" stratum-domain/src/main/java`
  - `rg "INSERT|UPDATE|DELETE" stratum-query/src/main/java`

### 阶段 4：接口与安全检查

- 接口改动时检查统一响应、traceId、错误码语义、OpenAPI 注解。
- 安全改动时检查 `401/403`、敏感日志、密钥来源、RBAC 与数据范围。

### 阶段 5：文档同步检查

- 若本次会话改动了仓库根目录普通文件，或顶层非点目录下的代码、构建脚本、配置文件，就必须同步更新 `docs/CONTEXT.md` 与 `docs/entropy/SESSION-HANDOFF.md`。
- 若本次会话仅改动顶层点目录（如 `.github/`、`.vscode/`）下的文件，可不强制同步上述双文档。
- 若未更新，这次变更直接判定为未完成。

### 阶段 6：Diff 复核

- 最后再看变更集合，确认没有误改、漏改与多余文件。

## 输出模板

使用以下格式汇总：

```text
VERIFICATION REPORT
Build: PASS/FAIL
Tests: PASS/FAIL
Architecture: PASS/FAIL
Security: PASS/FAIL
Docs Sync: PASS/FAIL
Overall: READY/NOT READY
Issues:
1. ...
```

## 常见反例

- 编辑结束后直接看 diff，不跑任何窄范围校验。
- 全仓命令失败后继续堆改动，失去定位能力。
- 已改代码却漏更 `CONTEXT` 或 `SESSION-HANDOFF`。
- 明知越过架构红线，仍以“后面再改”收尾。