package com.patrick.stratum.application.reuse;

import com.patrick.stratum.common.extension.StringExtensions;
import com.patrick.stratum.common.helper.TimeHelper;
import com.patrick.stratum.common.helper.ValidationHelper;

/**
 * Application 层 common 复用示例，负责展示命令上下文中对字符串、校验与时间工具的统一调用方式。
 * 适用场景：写用例中构建审计标签、命令标识与输入预校验。
 */
public final class ApplicationCommonReuseExample {

    private ApplicationCommonReuseExample() {
    }

    /**
     * 构建命令执行审计标签。
     *
     * @param commandName 命令名，不允许为空白。
     * @return 形如 command@2026-04-09T00:00:00Z 的审计标签。
     * @throws IllegalArgumentException 当 commandName 为空白时抛出异常。
     */
    public static String buildCommandAuditTag(String commandName) {
        String normalizedCommand = ValidationHelper.requireNonBlank(commandName, "commandName");
        return StringExtensions.trimToEmpty(normalizedCommand) + "@" + TimeHelper.formatIso(TimeHelper.nowUtc());
    }
}
