package com.patrick.stratum.application.command;

/**
 * 写用例的输入契约，以泛型关联结果类型，避免 HTTP 请求模型侵入应用层。
 *
 * @param <R> 用例结果的数据类型
 * @apiNote 示例：{@code record CreateNote(String title) implements Command<String> {}}
 */
public interface Command<R> {
}
