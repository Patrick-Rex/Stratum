package com.patrick.stratum.domain.idempotency;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 值对象：已完成写用例的幂等回执；只包含业务结果，不保存请求traceId。
 * @param key 服务端作用域与客户端键
 * @param requestDigest 请求内容的SHA-256摘要，小写十六进制
 * @param resultJson 可重放的业务结果JSON，由持久化端校验JSON语法
 */
public record IdempotencyReceipt(Key key, String requestDigest, String resultJson) {
    public static final int MAX_RESULT_BYTES = 262_144;

    /**
     * 限制摘要格式及结果大小，避免无界回执进入工作单元。
     * @param key 幂等键
     * @param requestDigest 请求摘要
     * @param resultJson 业务结果，最大256 KiB UTF-8
     * @throws IllegalArgumentException 摘要或结果不符合存储边界
     * @throws NullPointerException 幂等键为空
     * @apiNote 示例：new IdempotencyReceipt(key, digest, "{\"id\":\"...\"}")。
     */
    public IdempotencyReceipt {
        Objects.requireNonNull(key, "key");
        if (requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("请求摘要必须是SHA-256小写十六进制");
        }
        if (resultJson == null || resultJson.isBlank() || resultJson.length() > MAX_RESULT_BYTES
                || resultJson.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("幂等结果为空或超过256 KiB");
        }
    }

    /**
     * 值对象：保留大小写的复合键，作用域必须由服务端确定，不能直接采信客户端。
     * @param scope 操作与主体组成的服务端作用域，最长200字符
     * @param value 客户端Idempotency-Key，最长128字符
     */
    public record Key(String scope, String value) {
        /**
         * 验证ASCII键，拒绝空白、控制符及截断可能导致的键碰撞。
         * @param scope 服务端作用域
         * @param value 客户端键
         * @throws IllegalArgumentException 字段不符合字符及长度约束
         * @apiNote 示例：new IdempotencyReceipt.Key("material:create:user-uuid", "request-uuid")。
         */
        public Key {
            if (scope == null || !scope.matches("[A-Za-z0-9._:-]{1,200}")) {
                throw new IllegalArgumentException("幂等作用域非法");
            }
            if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IllegalArgumentException("Idempotency-Key非法");
            }
        }
    }
}
