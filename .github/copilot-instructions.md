# Stratum Workspace Instructions

- 本工作区采用“全局入口 + 节点二次加载”协作模式。
- 全局裁决优先级 MUST 为：[CONSTITUTION.md](../docs/CONSTITUTION.md) > [CONTEXT.md](../docs/CONTEXT.md) > [FORBIDDEN.md](../docs/FORBIDDEN.md) > [Spec清单.md](../docs/Spec清单.md) > 节点专题规范 > 其他参考文档。
- 团队协作时，优先使用共享 prompts：`/stratum-start` 用于建立会话上下文，`/stratum-node <NODE-ID>` 用于执行单个节点。
- 除非用户明确要求直接开始普通问答，否则执行 Stratum 节点任务前 MUST 先完成入口确认或节点二次加载确认。
- AI 修改任何代码时，所有新增或修改的类与方法 MUST 增加标准中文注释（类注释说明职责，方法注释说明用途、关键参数、返回值与异常条件）。
- AI 生成 Java 代码时 MUST 遵循统一注解规范：当前仓库已在根工程统一接入 Lombok（compileOnly + annotationProcessor）；AI 仍需按场景最小化使用，禁止仅为减少样板代码而滥用注解。
- `@Builder` 仅可用于 interface/query 的 Request/Response/DTO、application 的 Command/Result、common 的纯数据承载模型与测试数据构造；不得作为 Domain 聚合根、实体或带不变量对象的默认构造手段，此类对象 MUST 优先使用显式构造函数或命名工厂方法维护约束。
- AI 在 interface/query/application 搜索现有纯 DTO、Request、Response、Result 时，若当前模块无匹配类，不得为追求风格统一而额外新增样板类；应改为补充分层模板，并在后续新增真实业务模型时套用。
- 默认禁止使用 `@Data`；AI MUST 优先按需选择 `@Getter`、`@Setter`、`@ToString`、`@EqualsAndHashCode`、`@RequiredArgsConstructor` 等窄化注解，避免一次性生成过宽方法集合。
- Spring 管理组件 MUST 优先使用 final 字段 + 构造器注入；若 Lombok 已接入可优先使用 `@RequiredArgsConstructor`，否则显式编写构造函数。
- 输入校验 MUST 优先复用 `jakarta.validation` 注解与 `@Valid`/`@Validated`，常用范围包括 `@NotNull`、`@NotBlank`、`@NotEmpty`、`@Size`、`@Min`、`@Max`、`@Positive`、`@PositiveOrZero`、`@Pattern`；禁止在 interface/query/application 重复手写同类基础前置校验逻辑。
- 配置绑定 SHOULD 优先使用 `@ConfigurationProperties`；除单个临时配置外，避免扩散使用 `@Value`。写用例事务注解 `@Transactional` 仅允许出现在 Application 层，Query/Domain/Common 禁止新增。
- AI 生成代码时 MUST 按分层白名单套用 Lombok：纯数据承载类优先 `@Getter` + `@Builder`；Spring 托管的 controller/handler/service/factory 优先 `@RequiredArgsConstructor`，仅在确有日志需求时追加 `@Slf4j`；两类模板不得交叉混用。
- 由 Lombok 自动生成的 getter/setter/constructor/builder 方法视为生成代码，不强制逐一补写中文方法注释；但所有手写 public 类与 public 方法仍 MUST 保持完整中文注释。
- AI 在每次代码、构建脚本或配置文件发生变更后，MUST 同步更新 [docs/CONTEXT.md](../docs/CONTEXT.md) 与 [docs/entropy/SESSION-HANDOFF.md](../docs/entropy/SESSION-HANDOFF.md)。
- 若本次会话存在任何文件变更但未完成上述双文档更新，则本次执行视为未完成，不得结束交付。

## 会话收尾最小清单（MUST）

- 更新 [docs/CONTEXT.md](../docs/CONTEXT.md)：当前节点、已完成节点、待执行节点、关键决策、更新时间。
- 覆盖写入 [docs/entropy/SESSION-HANDOFF.md](../docs/entropy/SESSION-HANDOFF.md) 的“当前交接报告”段落，记录 completedNodes、changedFiles、validation、unresolved、nextNode。
- 最终回复中必须明确声明：CONTEXT 与 SESSION-HANDOFF 已更新，并给出对应文件路径。