package com.patrick.stratum.interfaceadapter.controller;

import java.net.URI;
import java.util.UUID;

import com.patrick.stratum.application.job.DeadLetterReplayCancellationService;
import com.patrick.stratum.application.job.DeadLetterReplayRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 管理员登记和取消死信重放任务，实际授权与状态竞争由应用服务处理。 */
@RestController
@RequestMapping(path = "/api/management/outbox/replay-tasks", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DeadLetterReplayTaskController {
    private final DeadLetterReplayRegistrationService registration;
    private final DeadLetterReplayCancellationService cancellation;

    /**
     * 只包含目标归档，操作者取自可信认证上下文。
     * @param archiveId 死信归档UUID
     * @apiNote 示例：{@code new Request(archiveId)}。
     */
    @Schema(name = "DeadLetterReplayTaskRequest")
    public record Request(@NotNull UUID archiveId) { }

    /**
     * 已登记或已取消的任务身份。
     * @param taskId 任务UUID
     * @apiNote 示例：{@code new Result(taskId)}。
     */
    @Schema(name = "DeadLetterReplayTaskResult")
    public record Result(UUID taskId) { }

    /**
     * 幂等登记任务，返回任务地址；不表示事件已发送或消费。
     * @param requestId 客户端提供的幂等UUID
     * @param request 待重放归档
     * @return 已接受任务及可查询地址
     * @apiNote 示例：POST /api/management/outbox/replay-tasks，携带Idempotency-Key及archiveId。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "管理员登记死信重放任务", description = "仅当前启用管理员；相同请求UUID及输入返回首次任务。登记不代表发送或消费成功。")
    @ApiResponse(responseCode = "202", description = "任务已登记", headers = @Header(name = "Location", description = "任务查询相对地址"))
    @ApiResponse(responseCode = "400", description = "请求参数或接口版本无效")
    @ApiResponse(responseCode = "401", description = "缺少有效Access")
    @ApiResponse(responseCode = "403", description = "当前账号不是启用管理员")
    @ApiResponse(responseCode = "409", description = "请求身份与首次登记不一致")
    public ResponseEntity<Result> register(@RequestHeader("Idempotency-Key") UUID requestId, @Valid @RequestBody Request request) {
        UUID taskId = registration.register(requestId, request.archiveId());
        return ResponseEntity.accepted().location(URI.create("/api/management/outbox/replay-tasks/" + taskId))
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(new Result(taskId));
    }

    /**
     * 幂等取消尚未开始的任务，每次重新验证当前管理员。
     * @param taskId 任务UUID
     * @return 已取消任务身份
     * @apiNote 示例：PUT /api/management/outbox/replay-tasks/{taskId}/cancellation。
     */
    @PutMapping(path = "/{taskId}/cancellation")
    @Operation(summary = "管理员取消未开始的死信重放任务", description = "重复取消返回同一任务；已经开始的任务不能取消。")
    @ApiResponse(responseCode = "200", description = "任务已取消")
    @ApiResponse(responseCode = "400", description = "任务身份或接口版本无效")
    @ApiResponse(responseCode = "401", description = "缺少有效Access")
    @ApiResponse(responseCode = "403", description = "当前账号不是启用管理员")
    @ApiResponse(responseCode = "404", description = "重放任务不存在")
    @ApiResponse(responseCode = "409", description = "任务已经开始")
    public ResponseEntity<Result> cancel(@PathVariable("taskId") UUID taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(new Result(cancellation.cancel(taskId)));
    }
}
