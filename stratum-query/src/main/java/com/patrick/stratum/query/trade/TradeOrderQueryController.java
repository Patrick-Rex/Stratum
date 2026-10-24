package com.patrick.stratum.query.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.trade.TradeOrder;
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

/** 采购销售摘要、详情及履约历史HTTP读取，所有数据访问经过QueryGateway。 */
@RestController
@RequestMapping(path = "/api/erp/orders/{kind}", version = "1")
@RequiredArgsConstructor
public class TradeOrderQueryController {
    private final QueryGateway queries;
    private final CurrentIdentity identities;

    /**
     * 按类别和白名单条件读取不含明细的订单分页。
     * @param kind 路由类别
     * @param parameters 列表参数
     * @return 订单摘要分页
     * @apiNote 示例：GET /api/erp/orders/PURCHASE?status=PARTIAL&sort=createdAt,desc。
     */
    @GetMapping
    @Operation(summary = "查询采购销售订单摘要", description = "queryCode=erp.order.list；默认创建时间倒序；ID/单号/金额/时间可排序，多个条件按AND组合，From/To须成对。")
    @Parameters({
        @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
        @Parameter(name = "pageSize", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "200")),
        @Parameter(name = "sort", in = ParameterIn.QUERY, array = @ArraySchema(schema = @Schema(type = "string", example = "createdAt,desc"))),
        @Parameter(name = "id", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "orderNumber", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
        @Parameter(name = "partyId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "warehouseId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "status", in = ParameterIn.QUERY, schema = @Schema(type = "string", allowableValues = {"PENDING", "PARTIAL", "COMPLETED"})),
        @Parameter(name = "totalAmount", in = ParameterIn.QUERY, schema = @Schema(type = "number", example = "12.50")),
        @Parameter(name = "totalAmountFrom", in = ParameterIn.QUERY, schema = @Schema(type = "number", example = "0")),
        @Parameter(name = "totalAmountTo", in = ParameterIn.QUERY, schema = @Schema(type = "number", example = "100")),
        @Parameter(name = "createdAt", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date-time")),
        @Parameter(name = "createdAtFrom", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date-time")),
        @Parameter(name = "createdAtTo", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date-time"))
    })
    public QueryResult<TradeOrderQuery.Row> list(@PathVariable("kind") TradeOrder.Kind kind,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        var query = QueryHttpParameters.input(() -> {
            var parsed = new QueryHttpParameters(parameters).exact("id", UUID.class).exact("orderNumber", String.class)
                    .exact("partyId", UUID.class).exact("warehouseId", UUID.class).exact("status", String.class)
                    .exact("totalAmount", BigDecimal.class).range("totalAmount", BigDecimal.class)
                    .exact("createdAt", Instant.class).range("createdAt", Instant.class).finish();
            return TradeOrderQuery.create(kind, parsed.page(), parsed.pageSize(), parsed.sorts(), parsed.filters());
        });
        return queries.execute(query, QueryHttpParameters.context(identities));
    }

    /**
     * 读取同一数据库语句快照中的订单与有界固定明细。
     * @param kind 路由类别
     * @param id 订单UUID
     * @return 详情，缺失时404
     * @apiNote 示例：GET /api/erp/orders/PURCHASE/{id}。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询采购销售订单详情", description = "queryCode=erp.order.detail；含order摘要及最多100条lines，明细按原行号返回；缺失或类别不符404。")
    public TradeOrderDetailQuery.Row detail(@PathVariable("kind") TradeOrder.Kind kind, @PathVariable("id") UUID id) {
        return queries.execute(TradeOrderDetailQuery.create(kind, id), QueryHttpParameters.context(identities)).getItems()
                .stream().findFirst().orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
    }

    /**
     * 在确认所属订单存在后读取固定历史批次，未履约返回空分页。
     * @param kind 路由类别
     * @param id 所属订单
     * @param parameters 批次分页与过滤
     * @return 不可变履约历史分页
     * @apiNote 示例：GET /api/erp/orders/PURCHASE/{id}/fulfillments?orderVersion=1。
     */
    @GetMapping("/{id}/fulfillments")
    @Operation(summary = "查询订单分批履约历史", description = "queryCode=erp.fulfillment.list；默认订单版本倒序，ID/当时版本/时间可排序；缺失或类别不符404，已存在未履约订单返回空分页。From/To成对，每批最多100行。")
    @Parameters({
        @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
        @Parameter(name = "pageSize", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "200")),
        @Parameter(name = "sort", in = ParameterIn.QUERY, array = @ArraySchema(schema = @Schema(type = "string", example = "orderVersion,desc"))),
        @Parameter(name = "postingId", in = ParameterIn.QUERY, description = "履约UUID", schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "orderVersion", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int64", example = "1")),
        @Parameter(name = "orderVersionFrom", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int64")),
        @Parameter(name = "orderVersionTo", in = ParameterIn.QUERY, schema = @Schema(type = "integer", format = "int64")),
        @Parameter(name = "createdAt", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date-time")),
        @Parameter(name = "createdAtFrom", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date-time")),
        @Parameter(name = "createdAtTo", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date-time"))
    })
    public QueryResult<FulfillmentQuery.Row> fulfillments(@PathVariable("kind") TradeOrder.Kind kind, @PathVariable("id") UUID id,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        var query = QueryHttpParameters.input(() -> {
            var parsed = new QueryHttpParameters(parameters).exact("postingId", "id", UUID.class).exact("orderVersion", Long.class)
                    .range("orderVersion", Long.class).exact("createdAt", Instant.class).range("createdAt", Instant.class).finish();
            return FulfillmentQuery.create(kind, id, parsed.page(), parsed.pageSize(), parsed.sorts(), parsed.filters());
        });
        var context = QueryHttpParameters.context(identities);
        var parent = TradeOrderQuery.create(kind, 1, 1, List.of(), List.of(new QuerySpec.FilterInput("id", QuerySpec.Operator.EQ, id, null)));
        if (queries.execute(parent, context).getItems().isEmpty()) throw new StratumException(CommonErrorCode.NOT_FOUND);
        return queries.execute(query, context);
    }
}
