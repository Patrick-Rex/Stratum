package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import com.patrick.stratum.query.web.QueryHttpParameters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** 管理员角色只读HTTP入口，不提供凭据字段，真实资格检查由查询处理器统一执行。 */
@RestController
@RequestMapping(path = "/api/auth/roles", version = "1")
@RequiredArgsConstructor
public class RoleManagementQueryController {
    private final QueryGateway queries;
    private final CurrentIdentity identities;

    /**
     * 按明确白名单读取角色元数据分页，文本包含按字面匹配。
     * @param parameters HTTP查询参数
     * @return 禁止缓存的角色页
     * @apiNote 示例：GET /api/auth/roles?sort=code,asc。
     */
    @GetMapping
    @Operation(summary = "管理员分页查询角色", description = "仅返回id、code、name、version；queryCode=auth.role.list。")
    @Parameters({
        @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
        @Parameter(name = "pageSize", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "200")),
        @Parameter(name = "sort", in = ParameterIn.QUERY, array = @ArraySchema(schema = @Schema(type = "string", example = "code,asc"))),
        @Parameter(name = "id", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "code", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
        @Parameter(name = "codeContains", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
        @Parameter(name = "name", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
        @Parameter(name = "nameContains", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
        @Parameter(name = "version", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int64")),
        @Parameter(name = "versionFrom", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int64")),
        @Parameter(name = "versionTo", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int64"))
    })
    public ResponseEntity<QueryResult<RoleManagementQuery.Row>> list(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        var query = QueryHttpParameters.input(() -> {
            var parsed = new QueryHttpParameters(parameters).exact("id", UUID.class).exact("code", String.class)
                    .contains("code").exact("name", String.class).contains("name").exact("version", Long.class).range("version", Long.class).finish();
            return RoleManagementQuery.create(parsed.page(), parsed.pageSize(), parsed.sorts(), parsed.filters());
        });
        return response(queries.execute(query, QueryHttpParameters.context(identities)));
    }

    /**
     * 读取角色公开管理字段，目标不存在返回404。
     * @param id 目标角色UUID
     * @return 禁止缓存的角色详情
     * @apiNote 示例：GET /api/auth/roles/{id}。
     */
    @GetMapping("/{id}")
    @Operation(summary = "管理员查询角色详情", description = "返回固定编码、当前名称及版本；queryCode=auth.role.list。")
    public ResponseEntity<RoleManagementQuery.Row> detail(@PathVariable("id") UUID id) {
        var query = RoleManagementQuery.create(1, 1, List.of(), List.of(new QuerySpec.FilterInput("id", QuerySpec.Operator.EQ, id, null)));
        var result = queries.execute(query, QueryHttpParameters.context(identities));
        return response(result.getItems().stream().findFirst().orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND)));
    }

    /**
     * 读取角色完整权限集合和观察版本，已有角色未获授权时返回空集合。
     * @param id 目标角色UUID
     * @return 最多1024项权限的完整快照
     * @apiNote 示例：GET /api/auth/roles/{id}/permissions。
     */
    @GetMapping("/{id}/permissions")
    @Operation(summary = "管理员查询角色权限", description = "返回id、version与permissions；queryCode=auth.role.permissions。")
    public ResponseEntity<RolePermissionsQuery.Row> permissions(@PathVariable("id") UUID id) {
        var result = queries.execute(RolePermissionsQuery.create(id), QueryHttpParameters.context(identities));
        return response(result.getItems().stream().findFirst().orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND)));
    }

    /**
     * 对管理读取统一禁止缓存。
     * @param <T> 无凭据读模型
     * @param body 查询结果
     * @return 带缓存保护的成功响应
     */
    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
