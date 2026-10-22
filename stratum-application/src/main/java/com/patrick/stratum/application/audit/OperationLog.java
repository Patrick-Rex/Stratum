package com.patrick.stratum.application.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需记录操作审计的应用用例；本契约不负责持久化或解析调用参数。
 *
 * @apiNote 示例：{@code @OperationLog(action = "create", targetType = "note")}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /**
     * 提供稳定的操作名称，避免使用含敏感参数的展示文本。
     *
     * @return 操作名称
     * @apiNote 示例：{@code "create"}。
     */
    String action();

    /**
     * 提供被操作对象的类型，供后续审计实现分类。
     *
     * @return 对象类型
     * @apiNote 示例：{@code "note"}。
     */
    String targetType();
}
