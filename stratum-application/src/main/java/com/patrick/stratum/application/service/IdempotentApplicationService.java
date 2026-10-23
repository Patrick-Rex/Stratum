package com.patrick.stratum.application.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.idempotency.IdempotencyReceipt;
import com.patrick.stratum.domain.idempotency.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 写用例幂等编排：在已有工作单元内重放结果或执行业务并原子保存回执。 */
@Component
@RequiredArgsConstructor
public class IdempotentApplicationService {
    private static final int MAX_REQUEST_BYTES = 262_144;
    private final IdempotencyRepository repository;
    private final JsonMapper json;

    /**
     * 按键重放已完成结果，同键不同请求返回409；竞争失败必须回滚整个工作单元。
     * @param <R> 业务结果类型
     * @param key 服务端绑定操作、版本和主体的键，授权检查须在调用前完成
     * @param request 已校验、规范化且可稳定序列化的请求DTO，不包含traceId
     * @param resultType 固定的结果DTO类型，不接受客户端指定
     * @param action 只产生同事务数据库写入的用例，不得直接调用外部服务
     * @return 首次执行或持久化重放的业务结果，可为空
     * @throws StratumException 请求内容冲突或并发唯一键冲突
     * @throws IllegalArgumentException 请求序列化超过256 KiB
     * @throws org.springframework.transaction.IllegalTransactionStateException 未处于工作单元内
     * @apiNote 示例：在CommandHandler中调用service.execute(key, command, Result.class, () -> create(command))。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public <R> R execute(IdempotencyReceipt.Key key, Object request, Class<R> resultType, Supplier<R> action) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(action, "action");
        String digest = digest(request);
        var existing = repository.find(key);
        if (existing.isPresent()) {
            var receipt = existing.orElseThrow();
            if (!receipt.requestDigest().equals(digest)) {
                throw new StratumException(CommonErrorCode.CONFLICT, "Idempotency-Key已用于不同请求");
            }
            return json.readValue(receipt.resultJson(), resultType);
        }
        R result = action.get();
        repository.append(new IdempotencyReceipt(key, digest, json.writeValueAsString(result)));
        return result;
    }

    /**
     * 对用例输入计算有界摘要，不保存请求原文或记录请求中的敏感数据。
     * @param request 稳定序列化的DTO
     * @return SHA-256十六进制摘要
     * @throws IllegalArgumentException 请求体超过上限
     */
    private String digest(Object request) {
        byte[] bytes = json.writeValueAsBytes(request);
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("幂等请求不能超过256 KiB");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行环境缺少标准SHA-256算法", failure);
        }
    }
}
