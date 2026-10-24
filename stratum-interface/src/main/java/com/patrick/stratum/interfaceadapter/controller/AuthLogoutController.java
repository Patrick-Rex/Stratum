package com.patrick.stratum.interfaceadapter.controller;

import com.patrick.stratum.application.auth.AuthLogoutApplicationService;
import com.patrick.stratum.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本人注销HTTP入口，身份由应用从可信上下文获取，响应不包含令牌或身份元数据。 */
@RestController
@RequestMapping(path = "/auth", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "stratum.auth.jwt.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AuthLogoutController {
    private final AuthLogoutApplicationService logout;

    /**
     * 注销当前会话。
     * @return 带traceId的空成功结果
     * @apiNote POST /auth/logout，只需Authorization: Bearer Access，不接收请求体身份。
     */
    @PostMapping("/logout")
    @Operation(summary = "退出当前会话", description = "当前会话的Access和Refresh立即失效，其他会话保持有效。")
    public ResponseEntity<ApiResponse<Void>> logout() {
        logout.logout(); return success();
    }

    /**
     * 注销当前账号全部既有会话。
     * @return 带traceId的空成功结果
     * @apiNote POST /auth/logout-all，只使用服务器认证身份；成功后需重新登录。
     */
    @PostMapping("/logout-all")
    @Operation(summary = "退出全部会话", description = "递增当前账号的撤销版本，所有旧Access和Refresh立即失效。")
    public ResponseEntity<ApiResponse<Void>> logoutAll() {
        logout.logoutAll(); return success();
    }

    /**
     * 构造不可缓存的标准成功响应。
     * @return 空业务结果，不暴露内部会话或版本
     */
    private static ResponseEntity<ApiResponse<Void>> success() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.success(null));
    }
}
