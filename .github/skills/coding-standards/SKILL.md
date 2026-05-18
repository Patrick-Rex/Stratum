---
name: coding-standards
description: Stratum 编码规范技能。用于约束命名、注释、复用、构建脚本与代码可维护性，作为本仓库的通用质量下限。
---

# Stratum 编码规范技能

## 何时启用

- 新增模块、类、方法、配置或构建脚本
- 重构已有实现
- 评审命名、注释、重复逻辑与可读性
- 补充 common 复用能力

## 先读这些规范

- docs/CONSTITUTION.md
- docs/FORBIDDEN.md
- docs/项目结构.md
- docs/CONTEXT.md

## Stratum 通用规则

- 所有回复、注释与说明统一使用中文。
- 所有 public 类必须有中文类注释；所有 public 方法必须有中文方法注释，至少包含用途、关键参数、返回值、异常条件与必要示例。
- 优先复用 `stratum-common` 的 `extension/helper/base/enums`，禁止在 `application/interface/query` 重复实现同类基础逻辑。
- 新增 Infrastructure 实现类使用 `XxxRepositoryImpl` 或 `XxxAdapterImpl`，避免命名为 `XxxService`。
- 子模块 `build.gradle` 不声明版本号，统一由根工程管理。
- 禁止无意义注释、单字母变量名、魔法数字、过深嵌套与超长方法。
- 先写清晰实现，再考虑抽象；没有明确复用场景时不要过度泛化。

## 注解与 Lombok 规范

- 当前仓库已由根工程统一接入 Lombok（compileOnly + annotationProcessor）。允许在受影响模块内使用 `@Builder`、`@Getter`、`@Setter`、`@Slf4j`、`@RequiredArgsConstructor` 等注解，但必须遵循最小化与场景约束。
- `@Builder` 只用于以下场景：interface/query 的 Request/Response/DTO、application 的 Command/Result、common 的纯数据承载结果模型、测试数据构造器。出现可选参数较多、链式组装明显优于长参数列表时才使用。
- Domain 聚合根、实体、值对象若存在不变量、生命周期约束或工厂校验，不要默认套用 `@Builder`；优先显式构造函数、静态工厂方法或领域命名工厂，确保规则收口在模型内部。
- 默认禁用 `@Data`。需要访问器时按需拆分为 `@Getter`、`@Setter`、`@ToString`、`@EqualsAndHashCode`，避免把可变性、相等性和字符串展开一次性全部放开。
- Spring 托管类优先 final 字段构造器注入；若已接入 Lombok，优先 `@RequiredArgsConstructor`，不要混用字段注入 `@Autowired`。
- Web 入参校验优先 `@Valid`、`@Validated` 配合 `jakarta.validation` 注解：`@NotNull`、`@NotBlank`、`@NotEmpty`、`@Size`、`@Min`、`@Max`、`@Positive`、`@PositiveOrZero`、`@Pattern`。简单基础校验不要重复手写。
- 配置对象优先 `@ConfigurationProperties`，避免同一配置组散落多个 `@Value`。事务注解 `@Transactional` 仅允许放在 Application 写用例边界，Query/Domain/Common 不得新增。
- 需要日志时，若未接入 Lombok，使用显式 `LoggerFactory`；若已接入并与当前模块风格一致，可使用 `@Slf4j`，但不要为了少写一行日志对象而引入 Lombok。
- Lombok 自动生成的 getter/setter/constructor/builder 方法可视为生成代码，不要求逐一补中文方法注释；但手写 public 方法与类注释规则不变。

## Lombok 白名单固定模板

- 仓库现状（2026-05-18）：`stratum-interface`、`stratum-query`、`stratum-application` 当前主代码暂无额外可迁移的纯 DTO/Request/Response/Result；除已落地的 `BasePageRequest` / `BasePageResult` 与其调用示例外，后续按以下模板用于新增真实业务模型。
- Interface 纯数据模型模板：`request/response/dto` 允许 `@Getter` + `@Builder`；禁止在这类类上混用 `@RequiredArgsConstructor`、`@Slf4j`、`@Data`。

```java
/**
 * Interface 响应 DTO，负责承载对外返回的只读数据。
 */
@Getter
@Builder
public class XxxResponse {

	private final String code;
	private final String name;
}
```

- Interface Spring 组件模板：`controller/factory/assembler` 允许 `@RequiredArgsConstructor`；仅在确有日志落点时追加 `@Slf4j`；禁止在组件类上使用 `@Builder`。

```java
/**
 * Interface 控制器，负责 HTTP 请求适配与响应返回。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class XxxController {

	private final XxxApplicationService applicationService;
}
```

- Query 纯数据模型模板：`dto/result` 允许 `@Getter` + `@Builder`；若存在分页归一化、默认值回退、防御性拷贝，优先保留显式构造函数并在构造函数上标注 `@Builder`，不要直接使用类型级 `@Builder`。

```java
/**
 * Query 结果对象，负责承载查询返回的只读结果。
 */
@Getter
@Builder
public class XxxQueryResult {

	private final Long id;
	private final String displayName;
}
```

- Query Spring 组件模板：`handler/gateway facade` 允许 `@RequiredArgsConstructor`；`@Slf4j` 仅在需要记录查询降级、缓存命中或白名单拒绝日志时启用；禁止 `@Builder`。

```java
/**
 * Query 处理器，负责执行业务无副作用的读取编排。
 */
@RequiredArgsConstructor
public class XxxQueryHandler {

	private final XxxQueryRepository queryRepository;
}
```

- Application 纯数据模型模板：`command/result` 允许 `@Getter` + `@Builder`；禁止在命令结果对象上使用 `@Slf4j`、`@RequiredArgsConstructor`、`@Data`。

```java
/**
 * Application 命令对象，负责承载写用例输入参数。
 */
@Getter
@Builder
public class XxxCommand {

	private final String aggregateId;
	private final String operatorId;
}
```

- Application Spring 组件模板：`command handler/use case service` 允许 `@RequiredArgsConstructor`；`@Slf4j` 仅在需要记录事务边界、幂等命中或关键分支时启用；禁止 `@Builder`。

```java
/**
 * Application 写用例处理器，负责业务编排与事务边界收口。
 */
@Slf4j
@RequiredArgsConstructor
public class XxxCommandHandler {

	private final XxxRepository repository;
}
```

- Domain 白名单：默认禁用 `@Builder`、`@RequiredArgsConstructor`、`@Slf4j`；`@Getter` 仅在不会泄露可变状态、且显式领域方法不具备更强语义时谨慎使用。
- Common 白名单：纯数据承载模型可使用 `@Getter` + `@Builder`；`helper/extension/util` 不应为减少样板代码而引入 `@Builder`。

## 命名与结构建议

- 聚合、实体、值对象、仓储接口放在 Domain 对应包下。
- `Command/Query/Result/Handler` 命名应直接体现业务动作。
- `DTO/Request/Response/Spec/Helper/Extensions` 使用职责型后缀。
- 常量应提取为语义化常量名，避免裸值散落。

## 重构检查点

- 是否能用早返回减少嵌套？
- 是否把基础通用逻辑沉淀到 common？
- 是否把业务判断留在 Domain，把编排留在 Application？
- 是否仅为当前真实需求建模，而不是为未来猜测设计？

## 常见反例

- 为一次性逻辑提前抽象三层接口。
- 在多个模块复制相同的字符串、分页、校验逻辑。
- 注释解释代码表面动作，而不解释设计原因。
- 构建脚本在子模块写死插件或依赖版本。

## 验证建议

- 变更后先运行受影响模块的 `gradlew :模块:build`
- 抽样检查 public 类和 public 方法是否补齐中文注释
- 抽样检查是否遗漏 `CONTEXT` 与 `SESSION-HANDOFF` 更新