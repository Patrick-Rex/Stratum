package com.patrick.stratum.interfaceadapter.controller;

import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.auth.RoleWarehouseManagementApplicationService;
import com.patrick.stratum.domain.auth.DataScopeType;
import com.patrick.stratum.domain.auth.WarehouseScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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

/** 管理员角色仓库配置HTTP写入口，独立版本和业务规则委托Application与Domain。 */
@RestController
@RequestMapping(path = "/api/auth/roles/{id}/warehouse-scope", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RoleWarehouseScopeController {
    private final RoleWarehouseManagementApplicationService management;

    /**
     * 独立配置整组替换输入，不接收角色编码或操作者。
     * @param version 仓库配置观察版本，不是角色API权限版本
     * @param scopeType 精确ALL或CUSTOM
     * @param warehouseIds 完整仓库UUID集合，ALL必须为空
     */
    @Schema(name = "ConfigureRoleWarehouseScopeRequest")
    public record Request(@NotNull @PositiveOrZero Long version,
            @NotNull @Pattern(regexp = "ALL|CUSTOM") @Schema(allowableValues = {"ALL", "CUSTOM"}, example = "CUSTOM") String scopeType,
            @NotNull @Size(max = WarehouseScope.MAX_WAREHOUSES) Set<@NotNull UUID> warehouseIds) { }

    /**
     * 已提交的独立配置版本。
     * @param id 角色UUID
     * @param version 仓库配置版本
     */
    @Schema(name = "RoleWarehouseScopeMutationResult")
    public record Result(UUID id, long version) { }

    /**
     * 以观察版本替换普通角色仓库配置，变更实时生效且不撤销成员会话。
     * @param id 角色UUID
     * @param request 已验证的完整配置
     * @return 禁止缓存的角色ID及配置版本
     * @apiNote 示例：PUT /api/auth/roles/{id}/warehouse-scope，含version、scopeType与warehouseIds。
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "管理员配置角色仓库范围", description = "仅当前启用管理员；配置版本独立于API权限版本。ALL须空集合，CUSTOM最多1024个仓库；管理员固定ALL不可改写，旧版本或仓库引用冲突409。")
    public ResponseEntity<Result> configure(@PathVariable("id") UUID id, @Valid @RequestBody Request request) {
        long version = management.configure(id, request.version(), DataScopeType.valueOf(request.scopeType()), request.warehouseIds());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(new Result(id, version));
    }
}
