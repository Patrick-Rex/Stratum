package com.patrick.stratum.interfaceadapter.controller;

import java.util.UUID;

import com.patrick.stratum.application.event.OutboxOriginalRecoveryApplicationService;
import com.patrick.stratum.domain.event.OutboxOriginalRecoveryRepository.Outcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员恢复原Outbox事件的HTTP入口；授权和事务由Application执行。 */
@RestController
@RequestMapping(path = "/api/management/outbox/events", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OutboxRecoveryController {
    private final OutboxOriginalRecoveryApplicationService recovery;

    /**
     * 首次请求的恢复结果，恢复表示重新允许领取，不表示发送或消费成功。
     * @param requestId 首次请求UUID
     * @param eventId 原事件UUID
     * @param outcome 恢复或已消费
     * @param previousFailures 恢复前累计失败次数，已消费时为空
     */
    @Schema(name = "OutboxOriginalRecoveryResult")
    public record Result(UUID requestId, UUID eventId, Outcome outcome, Integer previousFailures) { }

    /**
     * 恢复原失败事件或返回已消费事实，同请求身份始终返回首次结果。
     * @param eventId 原事件UUID
     * @param requestId 幂等请求UUID，作为Idempotency-Key传入
     * @return 无缓存的恢复回执
     * @apiNote 示例：POST /api/management/outbox/events/{eventId}/recoveries，携带UUID格式的Idempotency-Key。
     */
    @PostMapping(path = "/{eventId}/recoveries")
    @Operation(summary = "管理员恢复原Outbox事件", description = "要求当前启用管理员；原事件身份和正文不变。RECOVERED仅表示可重新领取，ALREADY_CONSUMED表示已有匹配消费凭据。")
    @ApiResponse(responseCode = "200", description = "返回首次恢复或已消费回执")
    @ApiResponse(responseCode = "400", description = "请求标识或接口版本无效")
    @ApiResponse(responseCode = "401", description = "缺少有效Access")
    @ApiResponse(responseCode = "403", description = "当前账号不是启用管理员")
    @ApiResponse(responseCode = "404", description = "原事件不存在")
    @ApiResponse(responseCode = "409", description = "请求身份或事件恢复资格冲突")
    public ResponseEntity<Result> recover(@PathVariable("eventId") UUID eventId,
            @RequestHeader("Idempotency-Key") UUID requestId) {
        var receipt = recovery.recover(requestId, eventId);
        Integer failures = receipt.previousFailures().isPresent() ? receipt.previousFailures().getAsInt() : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(new Result(receipt.requestId(), receipt.eventId(), receipt.outcome(), failures));
    }
}
