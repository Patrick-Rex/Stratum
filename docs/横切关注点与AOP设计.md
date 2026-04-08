# 横切关注点与AOP设计

## 1. 目标

把日志、审计、埋点、traceId 回传做成统一机制，避免业务代码重复实现。

## 2. 实现边界

- HTTP 请求日志：Filter 实现
- 统一响应封装与 traceId 回传：ResponseBodyAdvice
- 业务操作日志：Application 层注解 + AOP
- 性能埋点：Application 层 AOP
- 数据变更日志：Infrastructure 写入点
- 查询埋点：Query 层统一拦截
- 后台任务埋点：Job 执行器统一拦截

## 3. HTTP 日志过滤器（强制）

必须记录：
- method、path、status、latencyMs、traceId、clientIp、userId

必须支持：
- body 开关
- 最大长度限制
- 脱敏字段

## 4. 业务操作日志切面

注解：OperationLog

记录字段：
- operatorId
- action
- targetType
- targetId
- result
- errorCode
- traceId

落地：
- 审计表 audit_operation_log
- 同步结构化日志

## 5. 数据变更日志

触发点：
- 仓储写操作
- 事务提交前后

记录字段：
- tableName
- pk
- opType
- changesJson
- operatorId
- traceId

落地表：audit_data_change

## 6. 用例埋点与慢调用

- 对 Application 用例记录 Timer
- 阈值默认 500ms
- 超阈值输出 warn 日志

## 6.1 查询埋点

- 对 QueryHandler 记录 queryCode、durationMs、resultCount、traceId
- 慢查询阈值默认 300ms

## 6.2 后台任务埋点

- 对任务执行记录 jobCode、attempt、durationMs、result、traceId
- 重试事件必须输出结构化 warn 日志

## 6.3 缓存与锁埋点

- 缓存访问必须记录 cacheKey、hit/miss、loadDurationMs、traceId
- single-flight 必须记录合并请求数 mergedCount
- 分布式锁必须记录 lockKey、acquireResult、waitMs、holdMs、traceId
- 锁竞争失败与等待超时必须输出结构化 warn 日志

## 7. 动态开关（Nacos）

- observability.httpLog.enabled
- observability.httpLog.body.enabled
- observability.httpLog.body.maxBytes
- observability.slowCall.thresholdMs
- observability.query.slowThresholdMs
- observability.job.slowThresholdMs
- observability.cache.trace.enabled
- observability.lock.trace.enabled
- audit.operationLog.enabled
- audit.dataChange.enabled

## 8. 可执行验收

- 开关动态变更可生效
- 请求日志可关闭/开启并保持接口功能正常
- 注解切面可正确记录成功/失败场景
- 慢调用可触发 warn 日志
- 查询与后台任务埋点可稳定输出
- 缓存与分布式锁埋点可在日志与指标中按 traceId 关联
