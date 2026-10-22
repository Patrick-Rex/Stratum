package com.patrick.stratum.application.command;

/**
 * 成功用例的数据容器，不携带 HTTP 状态或响应 traceId；失败通过异常传播。
 *
 * @param <R> 业务返回值类型
 * @param data 返回值，无返回数据的用例可使用 null
 * @apiNote 示例：{@code new UseCaseResult<>(createdId)}。
 */
public record UseCaseResult<R>(R data) {
}
