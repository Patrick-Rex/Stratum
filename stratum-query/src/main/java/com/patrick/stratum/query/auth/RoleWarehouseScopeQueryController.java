package com.patrick.stratum.query.auth;

import java.util.UUID;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.web.QueryHttpParameters;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 管理员角色仓库配置HTTP只读入口，实际资格及完整集合检查在QueryGateway内执行。 */
@RestController
@RequestMapping(path = "/api/auth/roles/{id}/warehouse-scope", version = "1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RoleWarehouseScopeQueryController {
    private final QueryGateway queries;
    private final CurrentIdentity identities;

    /**
     * 获取独立配置版本、类型及完整指定集合，未知角色404。
     * @param id 目标角色UUID
     * @return 禁止缓存的完整配置
     * @apiNote 示例：GET /api/auth/roles/{id}/warehouse-scope；已有普通角色默认CUSTOM与空集合。
     */
    @GetMapping
    @Operation(summary = "管理员查询角色仓库范围", description = "仅当前启用管理员；返回id/version/scopeType/warehouseIds，version独立于角色API版本；queryCode=auth.role.warehouse-scope。")
    public ResponseEntity<RoleWarehouseScopeQuery.Row> detail(@PathVariable("id") UUID id) {
        var result = queries.execute(RoleWarehouseScopeQuery.create(id), QueryHttpParameters.context(identities));
        var row = result.getItems().stream().findFirst().orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(row);
    }
}
