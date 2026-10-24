package com.patrick.stratum.interfaceadapter.controller;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.application.auth.AuthSessionApplicationService.Tokens;
import com.patrick.stratum.common.trace.TraceIdContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本地登录与Refresh协议适配，不接受用户ID、会话ID或撤销版本输入。 */
@RestController
@RequestMapping(path = "/auth", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "stratum.auth.jwt.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AuthController {
    private final AuthSessionApplicationService authentication;

    /**
     * 登录输入；密码只写且日志描述隐藏全部内容。
     * @param username 精确登录标识
     * @param password 原始密码，UTF-8字节边界由密码端口继续校验
     */
    @Schema(name = "LoginRequest")
    public record LoginRequest(@NotBlank @Size(max = 64) String username,
            @NotNull @Size(min = 1, max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Schema(type = "string", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) char[] password) {
        /**
         * 隐藏登录输入。
         * @return 固定脱敏描述
         * @apiNote 不将原始用户名或密码写入日志。
         */
        @Override public String toString() { return "LoginRequest[内容已隐藏]"; }
    }

    /**
     * 刷新输入，只接受Refresh原文，不接受调用者自报身份。
     * @param refreshToken 有界的敏感Refresh原文
     */
    @Schema(name = "RefreshRequest")
    public record RefreshRequest(@NotBlank @Size(max = 16384)
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String refreshToken) {
        /**
         * 隐藏令牌输入。
         * @return 固定脱敏描述
         * @apiNote 不将Refresh原文写入日志。
         */
        @Override public String toString() { return "RefreshRequest[内容已隐藏]"; }
    }

    /**
     * 使用本地密码登录，成功响应禁止缓存。
     * @param request 已校验输入
     * @return 标准响应包装前的令牌对
     * @apiNote POST /auth/login，JSON包含username与password。
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "本地账号登录")
    public ResponseEntity<Tokens> login(@Valid @RequestBody LoginRequest request) {
        try { return response(authentication.login(request.username(), request.password(), TraceIdContext.getRequiredTraceId())); }
        finally { Arrays.fill(request.password(), '\0'); }
    }

    /**
     * 原子旋转Refresh并返回后继，Access不能用于此入口。
     * @param request 已校验Refresh输入
     * @return 禁止缓存的令牌对
     * @apiNote POST /auth/refresh，JSON包含refreshToken。
     */
    @PostMapping(path = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "旋转刷新令牌")
    public ResponseEntity<Tokens> refresh(@Valid @RequestBody RefreshRequest request) {
        return response(authentication.refresh(request.refreshToken()));
    }

    /**
     * 给敏感响应添加缓存保护，统一包装由现有Advice完成。
     * @param tokens 已确认会话的令牌对
     * @return HTTP成功响应
     */
    private static ResponseEntity<Tokens> response(Tokens tokens) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(tokens);
    }
}
