package com.patrick.stratum.query.masterdata;

import java.util.List;
import java.util.UUID;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.CurrentAuthorizationApplicationService;
import com.patrick.stratum.domain.masterdata.MasterDataPermissions;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.masterdata.MasterData;
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
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** 主数据列表及详情HTTP只读入口，固定类别并统一经过QueryGateway。 */
@RestController
@RequestMapping(path = "/api/erp/masters/{kind}", version = "1")
@RequiredArgsConstructor
public class MasterDataQueryController {
    private final QueryGateway queries;
    private final CurrentIdentity identities;
    private final CurrentAuthorizationApplicationService authorization;

    /**
     * 解析明确白名单参数并查询主数据分页。
     * @param kind 路由类别
     * @param parameters 查询参数
     * @return 统一分页结果
     * @apiNote 示例：GET /api/erp/masters/MATERIAL?nameContains=钢板&sort=code,asc。
     */
    @GetMapping
    @Operation(summary = "分页查询主数据", description = "精确与包含条件按AND组合；包含值按字面解释，sort可重复最多3次。需对应类别的erp.{material/customer/supplier/warehouse}.read权限；queryCode=erp.master.list")
    @Parameters({
        @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
        @Parameter(name = "pageSize", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "200")),
        @Parameter(name = "sort", in = ParameterIn.QUERY, array = @ArraySchema(schema = @Schema(type = "string", example = "code,asc"))),
        @Parameter(name = "id", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "code", in = ParameterIn.QUERY, schema = @Schema(type = "string", example = "M001")),
        @Parameter(name = "name", in = ParameterIn.QUERY, schema = @Schema(type = "string", example = "钢板")),
        @Parameter(name = "codeContains", in = ParameterIn.QUERY, schema = @Schema(type = "string", example = "M00")),
        @Parameter(name = "nameContains", in = ParameterIn.QUERY, schema = @Schema(type = "string", example = "钢"))
    })
    public QueryResult<MasterDataQuery.Row> list(@PathVariable("kind") MasterData.Kind kind,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        authorization.requireApi(MasterDataPermissions.read(kind));
        var query = QueryHttpParameters.input(() -> {
            var parsed = new QueryHttpParameters(parameters).exact("id", UUID.class).exact("code", String.class)
                    .exact("name", String.class).contains("code").contains("name").finish();
            return MasterDataQuery.create(kind, parsed.page(), parsed.pageSize(), parsed.sorts(), parsed.filters());
        });
        return queries.execute(query, QueryHttpParameters.context(identities));
    }

    /**
     * 按类别和完整UUID读取详情，缺失或跨类别返回404。
     * @param kind 路由类别
     * @param id 规范UUID
     * @return 主数据详情
     * @apiNote 示例：GET /api/erp/masters/MATERIAL/{id}。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询主数据详情", description = "不存在或类别不匹配返回404；需对应类别的erp.{material/customer/supplier/warehouse}.read权限；queryCode=erp.master.list")
    public MasterDataQuery.Row detail(@PathVariable("kind") MasterData.Kind kind, @PathVariable("id") UUID id) {
        authorization.requireApi(MasterDataPermissions.read(kind));
        var query = MasterDataQuery.create(kind, 1, 1, List.of(), List.of(new QuerySpec.FilterInput("id", QuerySpec.Operator.EQ, id, null)));
        var result = queries.execute(query, QueryHttpParameters.context(identities));
        return result.getItems().stream().findFirst().orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
    }
}
