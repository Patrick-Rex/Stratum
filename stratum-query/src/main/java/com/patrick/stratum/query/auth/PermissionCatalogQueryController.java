package com.patrick.stratum.query.auth;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.dto.QueryResult;
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

/** 管理员可授予权限目录HTTP入口，不代表调用者当前拥有的业务授权。 */
@RestController
@RequestMapping(path = "/api/auth/permissions", version = "1")
@RequiredArgsConstructor
public class PermissionCatalogQueryController {
    private final QueryGateway queries;
    private final CurrentIdentity identities;

    /**
     * 按精确类型和字面文本查询后端登记目录，仍要求当前可用管理员。
     * @param parameters 白名单HTTP参数
     * @return 禁止缓存的权限目录分页
     * @apiNote 示例：GET /api/auth/permissions?type=FEAT&sort=code,asc。
     */
    @GetMapping
    @Operation(summary = "管理员查询可授予权限目录", description = "type严格为API或FEAT，条目包含code/type/name；queryCode=auth.permission.catalog。")
    @Parameters({
        @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
        @Parameter(name = "pageSize", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "200")),
        @Parameter(name = "sort", in = ParameterIn.QUERY, array = @ArraySchema(schema = @Schema(type = "string", example = "code,asc"))),
        @Parameter(name = "code", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
        @Parameter(name = "codeContains", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
        @Parameter(name = "name", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
        @Parameter(name = "nameContains", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
        @Parameter(name = "type", in = ParameterIn.QUERY, schema = @Schema(type = "string", allowableValues = {"API", "FEAT"}))
    })
    public ResponseEntity<QueryResult<PermissionCatalogQuery.Row>> list(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        var query = QueryHttpParameters.input(() -> {
            var parsed = new QueryHttpParameters(parameters).exact("code", String.class).contains("code").exact("name", String.class)
                    .contains("name").exact("type", String.class).finish();
            return PermissionCatalogQuery.create(parsed.page(), parsed.pageSize(), parsed.sorts(), parsed.filters());
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(queries.execute(query, QueryHttpParameters.context(identities)));
    }
}
