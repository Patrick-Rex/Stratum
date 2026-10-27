package com.patrick.stratum.interfaceadapter.controller;

import com.patrick.stratum.application.event.OutboxRelayControlAdministrationService;
import com.patrick.stratum.domain.event.OutboxRelayControlRepository.State;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员查询和变更共享Relay暂停状态的HTTP入口。
 * <p>示例：GET /api/management/outbox/relay 查询状态后携带版本更新。</p>
 */
@RestController
@RequestMapping(path = "/api/management/outbox/relay", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OutboxRelayControlController {
    private final OutboxRelayControlAdministrationService administration;

    /**
     * 替换共享暂停状态的观察版本和目标。
     * @param revision 当前观察版本
     * @param paused 是否暂停后续批次
     * @apiNote 示例：{@code new Request(0L, false)}按版本零恢复。
     */
    @Schema(name = "OutboxRelayControlRequest")
    public record Request(@NotNull @PositiveOrZero Long revision, @NotNull Boolean paused) { }

    /**
     * 查询当前共享暂停状态和版本。
     * @return 禁止缓存的权威状态
     * @apiNote 示例：GET /api/management/outbox/relay，使用版本1和管理员Access。
     */
    @GetMapping
    @Operation(summary = "管理员查询Relay共享暂停状态", description = "仅当前启用管理员可查询；版本用于后续更新。")
    @ApiResponse(responseCode = "200", description = "返回当前暂停状态和版本")
    @ApiResponse(responseCode = "400", description = "接口版本无效")
    @ApiResponse(responseCode = "401", description = "缺少有效Access")
    @ApiResponse(responseCode = "403", description = "当前账号不是启用管理员")
    public ResponseEntity<State> current() { return response(administration.current()); }

    /**
     * 按观察版本变更状态，已准入批次按实际结果收尾。
     * @param request 已验证的版本和目标
     * @return 禁止缓存的已提交状态
     * @apiNote 示例：PUT /api/management/outbox/relay，正文包含revision和paused。
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "管理员更新Relay共享暂停状态", description = "使用观察版本；旧版本返回409，即使目标相同也不能覆盖。已准入批次继续收尾。")
    @ApiResponse(responseCode = "200", description = "返回已提交的暂停状态和版本")
    @ApiResponse(responseCode = "400", description = "请求参数或接口版本无效")
    @ApiResponse(responseCode = "401", description = "缺少有效Access")
    @ApiResponse(responseCode = "403", description = "当前账号不是启用管理员")
    @ApiResponse(responseCode = "409", description = "观察版本过期或状态竞争")
    public ResponseEntity<State> change(@Valid @RequestBody Request request) {
        return response(administration.change(request.revision(), request.paused()));
    }

    /**
     * 为权威状态添加禁止缓存标头。
     * @param state 已读取或提交的状态
     * @return HTTP响应
     */
    private ResponseEntity<State> response(State state) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(state);
    }
}
