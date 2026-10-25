package com.patrick.stratum.interfaceadapter.controller;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.patrick.stratum.application.auth.AccountCreationApplicationService;
import com.patrick.stratum.application.auth.AccountRoleApplicationService;
import com.patrick.stratum.application.auth.AccountStatusApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 账号管理写协议入口，当前管理员检查、并发及撤销全部委托Application用例。 */
@RestController
@RequestMapping(path = "/api/auth/accounts", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccountManagementController {
    private final AccountCreationApplicationService creation;
    private final AccountStatusApplicationService status;
    private final AccountRoleApplicationService memberships;

    /**
     * 创建普通账号输入，不提供初始化、角色或操作者字段。
     * @param username 固定用户名
     * @param password 只写密码，编码边界由密码端口检查
     */
    @Schema(name = "CreateAccountRequest")
    public record CreateRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String username,
            @NotNull @Size(min = 1, max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Schema(type = "string", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) char[] password) {
        /** {@inheritDoc} */
        @Override public String toString() { return "CreateAccountRequest[内容已隐藏]"; }
    }

    /**
     * 启停输入，必须显式携带观察版本和目标状态。
     * @param version 账号观察版本
     * @param enabled 目标启用状态
     */
    @Schema(name = "ChangeAccountStatusRequest")
    public record StatusRequest(@NotNull @PositiveOrZero Long version, @NotNull Boolean enabled) { }

    /**
     * 完整成员关系替换输入，空集合表示移除全部角色。
     * @param version 账号观察版本
     * @param roleIds 最多64个角色UUID
     */
    @Schema(name = "ReplaceAccountRolesRequest")
    public record RolesRequest(@NotNull @PositiveOrZero Long version, @NotNull @Size(max = 64) Set<@NotNull UUID> roleIds) { }

    /**
     * 账号写入结果，不包含凭据或角色快照。
     * @param id 目标账号UUID
     * @param version 新账号版本
     */
    @Schema(name = "AccountMutationResult")
    public record Result(UUID id, long version) { }

    /**
     * 创建默认无角色的普通账号，密码数组在应用返回或异常后清零。
     * @param request 已验证输入
     * @return 201及账号ID和初始版本，Location指向详情
     * @apiNote 示例：POST /api/auth/accounts，请求包含username和password。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "管理员创建普通账号", description = "无默认角色或业务权限；重复用户名返回409，密码仅用于散列。")
    @ApiResponse(responseCode = "201", description = "普通账号创建成功")
    public ResponseEntity<Result> create(@Valid @RequestBody CreateRequest request) {
        try {
            UUID id = creation.create(request.username(), request.password());
            return ResponseEntity.created(URI.create("/api/auth/accounts/" + id)).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache").body(new Result(id, 0));
        } finally { Arrays.fill(request.password(), '\0'); }
    }

    /**
     * 按版本启停账号，最后管理员保护或旧版本返回409。
     * @param id 目标账号
     * @param request 已验证状态和版本
     * @return 更新后的账号版本
     * @apiNote 示例：PATCH /api/auth/accounts/{id}/status，请求包含version和enabled。
     */
    @PatchMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "管理员启停账号", description = "启停均撤销目标旧会话；不能停用最后一个可用管理员。")
    public ResponseEntity<Result> changeStatus(@PathVariable("id") UUID id, @Valid @RequestBody StatusRequest request) {
        return response(id, status.changeEnabled(id, request.version(), request.enabled()));
    }

    /**
     * 按版本替换账号全部角色，目标不存在返回404，未知角色或版本冲突返回409。
     * @param id 目标账号
     * @param request 完整角色集合和版本
     * @return 更新后的账号版本
     * @apiNote 示例：PUT /api/auth/accounts/{id}/roles，请求包含version和roleIds数组。
     */
    @PutMapping(path = "/{id}/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "管理员替换账号角色", description = "最多64个角色，空集合清空；变更撤销目标旧会话并保护最后管理员。")
    public ResponseEntity<Result> replaceRoles(@PathVariable("id") UUID id, @Valid @RequestBody RolesRequest request) {
        return response(id, memberships.replace(id, request.version(), request.roleIds()));
    }

    /**
     * 为管理写结果添加禁止缓存协议。
     * @param id 目标账号
     * @param version 已提交版本
     * @return 200管理结果
     */
    private static ResponseEntity<Result> response(UUID id, long version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(new Result(id, version));
    }
}
