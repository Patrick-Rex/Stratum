---
name: easy-query
description: EasyQuery ORM 完整指南。当用户提到 EasyQuery、easy-query、@EntityProxy、@Table、@Navigate、EasyEntityQuery、queryable、toList、toPageResult、insertable、updatable、deletable、com.easy-query、sql-springboot-starter、分库分表、读写分离、ORM 强类型查询，或在 Java/Kotlin 项目中处理数据库 CRUD 时激活此 skill。
---
# EasyQuery ORM 源码索引

> **重要**：使用此 skill 时，所有回复和说明必须使用中文。

## 概述

EasyQuery 是高性能 Java/Kotlin ORM 框架，支持强类型表达式、分库分表、读写分离和 13+ 种数据库方言。

| 仓库 | 本地路径 |
|------|----------|
| 源码 | `C:\Users\comac\.copilot\skills\easyquery\easy-query-main\` |
| 文档 | `C:\Users\comac\.copilot\skills\easyquery\easy-query-doc-main\` |

**官方文档**：https://www.easy-query.com/easy-query-doc/

---

## 源码仓库目录结构

```
easy-query-main/
├── sql-core/                     # 核心 ORM 引擎
│   └── src/main/java/com/easy/query/core/
│       ├── annotation/           # 所有注解定义（@Table、@Column、@Navigate、@EntityProxy 等）
│       │   └── proxy/            # 代理相关注解
│       ├── api/
│       │   ├── client/           # EasyQueryClient 接口和默认实现
│       │   ├── def/              # EasyEntityQuery 等 API 接口定义
│       │   └── dynamic/          # 动态查询 API
│       ├── expression/           # SQL 表达式树（Select/Where/Group/Order）
│       ├── sharding/             # 分库分表核心逻辑
│       ├── metadata/             # 实体元数据解析
│       ├── datasource/           # 数据源抽象
│       ├── migration/            # 数据库迁移
│       └── context/              # 查询上下文
│
├── sql-processor/                # Java APT 注解处理器（编译期生成代理类）
├── sql-ksp-processor/            # Kotlin KSP 处理器
│
├── sql-platform/
│   └── sql-api-proxy/            # EntityProxy 代理基类和 API
│       └── src/main/java/com/easy/query/api/proxy/
│           ├── base/             # AbstractBasicProxyEntity 等基类
│           ├── client/           # 代理查询客户端
│           └── entity/           # 实体代理扩展
│
├── sql-test/                     # ⭐ 完整集成测试（最佳参考）
│   └── src/main/java/com/easy/query/test/
│       ├── entity/               # 实体类示例（含各种注解用法）
│       │   ├── company/          # 一对多关系示例
│       │   ├── m2m/              # 多对多关系示例
│       │   └── proxy/            # EntityProxy 代理实体示例
│       ├── crud/                 # CRUD 操作测试
│       ├── navigate/             # 关联查询测试
│       ├── dto/                  # DTO 查询和 whereObject 示例
│       │   ├── autodto/          # 自动 DTO 生成
│       │   ├── proxy/            # 代理 DTO 示例
│       │   └── Blog*Request.java # whereObject 请求类示例
│       ├── sharding/             # 分库分表配置示例
│       ├── func/                 # 内置函数用法
│       ├── logicdel/             # 逻辑删除测试
│       ├── version/              # 乐观锁测试
│       ├── encryption/           # 字段加密测试
│       ├── interceptor/          # 拦截器示例
│       └── mysql8/ pgsql/ sqlite/ # 数据库特定测试
│
├── sql-db-support/               # 数据库方言
│   ├── sql-mysql/
│   ├── sql-pgsql/
│   ├── sql-oracle/
│   ├── sql-mssql/
│   ├── sql-sqlite/
│   ├── sql-h2/
│   ├── sql-clickhouse/
│   └── sql-dameng/ 等...
│
├── sql-extension/                # 框架集成
│   ├── sql-springboot-starter/   # Spring Boot 2/3 Starter
│   ├── sql-springboot4-starter/  # Spring Boot 4 Starter
│   ├── sql-solon-plugin/         # Solon 插件
│   ├── sql-cache/                # 缓存扩展
│   └── sql-search/               # 全文搜索扩展
│
└── sql-test/                     # 集成测试和用法示例
```

---

## 文档仓库目录结构

```
easy-query-doc-main/src/
├── startup/          # 快速开始、安装配置
├── guide/            # 核心指南
│   ├── spring-boot.md        # Spring Boot 集成
│   ├── kotlin.md             # Kotlin 使用指南
│   ├── kapt.md               # Kotlin APT 配置
│   └── sb-multi-datasource.md  # 多数据源
├── ability/          # 核心能力
│   ├── select/               # 查询（返回结果类型等）
│   ├── insert.md             # 插入
│   ├── delete.md             # 删除
│   ├── batch.md              # 批量操作
│   ├── insertOrUpdate.md     # 插入或更新
│   └── native-sql.md         # 原生 SQL
├── annotation/       # 所有注解详细说明
├── navigate/         # 关联查询
│   ├── definition.md         # @Navigate 定义
│   ├── direct2one.md         # 一对一/多对一
│   └── many2many1.md         # 多对多
├── adv/              # 高级功能（分库分表、读写分离等）
├── dto-query/        # DTO 查询模式、whereObject
├── sub-query/        # 子查询
├── func/             # 内置函数
├── performance/      # 性能优化
├── prop/             # 属性转换（枚举、JSON）
└── savable/          # 聚合根保存模式
```

---

## 关键类路径速查

| 查询目标 | 路径 |
|---------|------|
| `@Table`、`@Column`、`@EntityProxy` 注解定义 | `sql-core/src/main/java/com/easy/query/core/annotation/` |
| `@Navigate` 关联注解 | `sql-core/.../annotation/Navigate.java` |
| `@LogicDelete`、`@Version` 注解 | `sql-core/.../annotation/LogicDelete.java` |
| `EasyQueryClient` 接口 | `sql-core/.../api/client/EasyQueryClient.java` |
| 代理基类 `AbstractBasicProxyEntity` | `sql-platform/sql-api-proxy/.../base/AbstractBasicProxyEntity.java` |
| Spring Boot 自动配置 | `sql-extension/sql-springboot-starter/src/main/java/` |
| APT 处理器入口 | `sql-processor/src/main/java/` |
| MySQL 方言实现 | `sql-db-support/sql-mysql/src/main/java/` |

---

## 搜索策略

### 场景化路径

| 目标 | 优先搜索路径 |
|------|-------------|
| 某注解有哪些属性 | `sql-core/.../annotation/<注解名>.java` |
| APT 生成代理失败 | `sql-processor/` 源码 + 文档 `src/startup/` |
| Spring Boot 配置项 | `sql-extension/sql-springboot-starter/` 的 `properties/` 或 `autoconfigure/` |
| 了解 `@Navigate` 查询行为 | 文档 `src/navigate/` 目录下的 md 文件 |
| whereObject DTO 注解 | 文档 `src/dto-query/` |
| 分库分表配置 | `sql-core/.../sharding/` + 文档 `src/adv/` |
| 数据库方言特定行为 | `sql-db-support/sql-<dbname>/` |
| Kotlin/KSP 用法 | `sql-ksp-processor/` + 文档 `src/guide/kotlin.md` |
| 批量操作优化 | 文档 `src/ability/batch.md` |

### 使用 Copilot CLI 工具

> EasyQuery 源码不在 IDE 打开的项目中，**不能使用 JetBrains MCP 工具**。请使用 Copilot CLI 内置的 grep / glob / view 工具，以及 LSP 的 `documentSymbol` 查看文件结构。
>
> ⚠️ LSP 的 `workspaceSymbol` / `findReferences` 等跨文件操作**不可用于外部源码**，跨文件搜索请用 grep。

```
# 定义源码和文档根路径变量（后续示例中使用）
EQ_SRC = C:\Users\comac\.copilot\skills\easyquery\easy-query-main
EQ_DOC = C:\Users\comac\.copilot\skills\easyquery\easy-query-doc-main

# 按文件名模式查找（glob 工具）
glob(pattern="**/Navigate.java", path=EQ_SRC)
glob(pattern="**/*AutoConfiguration.java", path=EQ_SRC)
glob(pattern="**/sql-test/**/*.java", path=EQ_SRC)

# 全文搜索文件内容（grep 工具，基于 ripgrep）
grep(pattern="toPageResult", path=EQ_SRC, glob="*.java")
grep(pattern="whereObject", path=EQ_DOC, glob="*.md")
grep(pattern="@Navigate", path=EQ_SRC, glob="**/sql-test/**/*.java")

# 查看文件内所有符号结构（LSP documentSymbol）
lsp(operation="documentSymbol", file="<文件绝对路径>")

# 阅读具体文件（view 工具）
view(path="<具体文件绝对路径>")
view(path="<文件路径>", view_range=[10, 50])  # 只看第 10-50 行
```

### ⭐ 优先查阅测试类

**`sql-test/` 是最好的用法参考**，比官方文档更贴近真实代码：

| 需要了解 | 测试类路径 |
|---------|-----------|
| 实体定义范式（含所有注解组合）| `sql-test/.../entity/` 下各实体类 |
| 一对多/多对多关系定义 | `sql-test/.../entity/company/` 和 `m2m/` |
| whereObject DTO 查询 | `sql-test/.../dto/Blog*Request.java` |
| 逻辑删除配置 | `sql-test/.../logicdel/` |
| 分库分表路由配置 | `sql-test/.../sharding/` |
| 拦截器实现 | `sql-test/.../interceptor/` |

---

## 模块依赖速查

| 项目类型 | 必须依赖 |
|----------|---------|
| Spring Boot 2/3 | `sql-springboot-starter` + `sql-processor` + `sql-<db>` |
| Spring Boot 4 | `sql-springboot4-starter` + `sql-processor` + `sql-<db>` |
| Kotlin + Spring | `sql-springboot-starter` + `sql-ksp-processor`（KSP，非 kapt）+ `sql-<db>` |
| 非框架 Java | `sql-api-proxy` + `sql-processor` + `sql-<db>` |

支持数据库：MySQL, PostgreSQL, Oracle, SQL Server, H2, SQLite, ClickHouse, 达梦, KingbaseES, GaussDB, DuckDB, DB2, TDengine
