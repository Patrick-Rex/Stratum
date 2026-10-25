package com.patrick.stratum.interfaceadapter.controller;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.auth.CurrentAuthorizationApplicationService;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本人功能权限HTTP入口，不接受用户标识，不返回API授权、凭据或内部会话信息。 */
@RestController
@RequestMapping(path = "/auth", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "stratum.auth.jwt.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CurrentAccountController {
    private final CurrentAuthorizationApplicationService authorization;

    /**
     * 前端展示所需的当前账号及功能编码，集合有界并按编码稳定排序。
     * @param userId 当前已认证账号UUID
     * @param featurePermissions 当前FEAT编码，不替代后端API检查
     */
    @Schema(name = "CurrentAccountResult")
    public record Result(UUID userId,
            @ArraySchema(maxItems = AccountPermissions.Snapshot.MAX_PERMISSIONS, schema = @Schema(type = "string"))
            List<String> featurePermissions) {
        /**
         * 防御性复制展示集合，拒绝缺失主体及超界输入。
         * @param userId 当前账号UUID
         * @param featurePermissions 功能编码
         * @apiNote 示例：{@code new Result(userId, List.of("erp.inventory"))}。
         */
        public Result {
            Objects.requireNonNull(userId, "账号标识不能为空");
            featurePermissions = List.copyOf(featurePermissions);
            if (featurePermissions.size() > AccountPermissions.Snapshot.MAX_PERMISSIONS) throw new IllegalArgumentException("功能权限超出上限");
        }
    }

    /**
     * 下发当前启用账号的实时功能权限，仅用于前端展示。
     * @return 禁止缓存的当前账号结果，普通账号可返回空功能集合
     * @apiNote 示例：GET /auth/me，携带Access；请求中的自报用户不参与身份判定。
     */
    @GetMapping("/me")
    @Operation(summary = "查询本人功能权限", description = "仅返回当前账号userId和featurePermissions；每次读取实时FEAT授权，不替代后端API校验。")
    @ApiResponse(responseCode = "200", description = "当前账号与功能权限", headers = {
        @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
        @Header(name = "Pragma", schema = @Schema(type = "string", example = "no-cache"))
    })
    public ResponseEntity<Result> me() {
        var snapshot = authorization.current();
        var result = new Result(snapshot.userId(), snapshot.codes(Permission.Type.FEAT).stream().sorted().toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(result);
    }
}
