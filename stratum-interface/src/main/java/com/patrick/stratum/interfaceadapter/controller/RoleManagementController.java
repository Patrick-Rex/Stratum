package com.patrick.stratum.interfaceadapter.controller;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.patrick.stratum.application.auth.RoleManagementApplicationService;
import com.patrick.stratum.domain.auth.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** 管理员角色写协议入口，目录、保留角色与并发规则由Application和Domain执行。 */
@RestController
@RequestMapping(path = "/api/auth/roles", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RoleManagementController {
    private final RoleManagementApplicationService management;

    /**
     * 空权限角色创建输入。
     * @param code 固定角色编码
     * @param name 展示名称
     */
    @Schema(name = "CreateRoleRequest")
    public record CreateRequest(@NotNull @Size(max = 96) @Pattern(regexp = "[a-z][a-z0-9]*(?:[.:-][a-z0-9]+)*") String code,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cc}]*") String name) { }

    /**
     * 精确权限输入，编码与类型组合必须在后端目录中登记。
     * @param code 权限编码
     * @param type API或FEAT
     */
    @Schema(name = "RolePermissionInput")
    public record PermissionInput(@NotNull @Size(max = 96) @Pattern(regexp = "[a-z][a-z0-9]*(?:[.:-][a-z0-9]+)*") String code,
            @NotNull Permission.Type type) { }

    /**
     * 角色名称与整组权限配置，不接收固定code或操作者。
     * @param version 角色观察版本
     * @param name 新名称
     * @param permissions 完整权限集合，空集合清空
     */
    @Schema(name = "ConfigureRoleRequest")
    public record ConfigureRequest(@NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[^\\p{Cc}]*") String name,
            @NotNull @Size(max = 1024) Set<@NotNull @Valid PermissionInput> permissions) { }

    /**
     * 已提交角色版本，不返回可变领域对象。
     * @param id 角色UUID
     * @param version 新持久化版本
     */
    @Schema(name = "RoleMutationResult")
    public record Result(UUID id, long version) { }

    /**
     * 创建默认无权限普通角色，保留编码不允许创建。
     * @param request 已验证编码和名称
     * @return 201、Location及初始版本
     * @apiNote 示例：POST /api/auth/roles，请求包含code和name。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "管理员创建普通角色", description = "默认空权限；保留编码拒绝，重复编码返回409。")
    @ApiResponse(responseCode = "201", description = "角色创建成功")
    public ResponseEntity<Result> create(@Valid @RequestBody CreateRequest request) {
        UUID id = management.create(request.code(), request.name());
        return ResponseEntity.created(URI.create("/api/auth/roles/" + id)).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(new Result(id, 0));
    }

    /**
     * 以观察版本替换角色名称和全部权限，目录外项及保留角色配置拒绝。
     * @param id 角色UUID
     * @param request 已验证输入
     * @return 200及新角色版本
     * @apiNote 示例：PUT /api/auth/roles/{id}，请求包含version、name和permissions数组。
     */
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "管理员配置角色权限", description = "权限即时由后端读取；不能配置administrator保留角色，旧版本返回409。")
    public ResponseEntity<Result> configure(@PathVariable("id") UUID id, @Valid @RequestBody ConfigureRequest request) {
        var permissions = request.permissions().stream().map(input -> new Permission(input.code(), input.type())).collect(Collectors.toUnmodifiableSet());
        long version = management.configure(id, request.version(), request.name(), permissions);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(new Result(id, version));
    }
}
