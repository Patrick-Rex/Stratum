package com.patrick.stratum.domain.auth;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 值对象：类型化的精确权限标识，不支持通配符或隐式前缀匹配。
 * @param code 1至96位小写ASCII分段标识
 * @param type API调用或前端功能类型
 */
public record Permission(String code, Type type) {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9]*(?:[.:-][a-z0-9]+)*");

    /** 规范限定的权限类型；FEAT不能替代服务端API授权。 */
    public enum Type { API, FEAT }

    /**
     * 保持有界、精确权限标识，不裁剪或自动改变大小写。
     * @param code 权限编码
     * @param type 权限类型
     * @throws IllegalArgumentException 编码为空、超长或含非法字符
     * @apiNote 示例：{@code new Permission("erp.material.read", Permission.Type.API)}。
     */
    public Permission {
        if (code == null || code.length() > 96 || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("权限编码格式不合法");
        }
        Objects.requireNonNull(type, "权限类型不能为空");
    }
}
