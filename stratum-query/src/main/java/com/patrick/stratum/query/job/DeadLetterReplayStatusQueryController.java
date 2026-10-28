package com.patrick.stratum.query.job;

import java.util.UUID;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.web.QueryHttpParameters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员查询死信重放任务状态；查询网关复核当前授权和真实消费凭据。 */
@RestController
@RequestMapping(path = "/api/management/outbox/replay-tasks", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class DeadLetterReplayStatusQueryController {
    private final QueryGateway queries;
    private final CurrentIdentity identities;

    /**
     * 查询单个任务的安全状态；只有真实消费成功才返回SUCCEEDED和百分之百。
     * @param taskId 任务UUID
     * @return 禁止缓存的任务状态
     * @apiNote 示例：GET /api/management/outbox/replay-tasks/{taskId}。
     */
    @GetMapping(path = "/{taskId}")
    @Operation(summary = "管理员查询死信重放任务状态", description = "仅当前启用管理员；登记或发送确认不表示消费成功。")
    @ApiResponse(responseCode = "200", description = "返回任务状态、进度、安全失败分类和首次trace")
    @ApiResponse(responseCode = "400", description = "任务身份或接口版本无效")
    @ApiResponse(responseCode = "401", description = "缺少有效Access")
    @ApiResponse(responseCode = "403", description = "当前账号不是启用管理员")
    @ApiResponse(responseCode = "404", description = "重放任务不存在")
    public ResponseEntity<DeadLetterReplayStatusQuery.Row> detail(@PathVariable("taskId") UUID taskId) {
        var result = queries.execute(DeadLetterReplayStatusQuery.create(taskId), QueryHttpParameters.context(identities));
        var row = result.getItems().stream().findFirst().orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(row);
    }
}
