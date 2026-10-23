package com.patrick.stratum.query.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** 库存余额与不可变流水HTTP查询适配，不开放库存调整写入口。 */
@RestController
@RequestMapping(path = "/api/erp", version = "1")
@RequiredArgsConstructor
public class InventoryQueryController {
    private final QueryGateway queries;

    /**
     * 按物料、仓库及精确数量条件读取余额。
     * @param parameters 白名单查询参数
     * @return 库存余额分页
     * @apiNote 示例：GET /api/erp/stock-balances?materialId={uuid}&quantityFrom=0&quantityTo=10。
     */
    @GetMapping("/stock-balances")
    @Operation(summary = "查询库存余额", description = "queryCode=erp.stock.balance.list；ID/物料/仓库/数量可排序，默认ID；条件按AND组合。")
    @Parameters({
        @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
        @Parameter(name = "pageSize", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "200")),
        @Parameter(name = "sort", in = ParameterIn.QUERY, array = @ArraySchema(schema = @Schema(type = "string", example = "quantity,asc"))),
        @Parameter(name = "id", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "quantity", in = ParameterIn.QUERY, schema = @Schema(type = "number", example = "0.125")),
        @Parameter(name = "quantityFrom", in = ParameterIn.QUERY, description = "闭区间下界，须同时提供quantityTo", schema = @Schema(type = "number", example = "0.125")),
        @Parameter(name = "quantityTo", in = ParameterIn.QUERY, description = "闭区间上界，须同时提供quantityFrom", schema = @Schema(type = "number", example = "2")),
        @Parameter(name = "materialId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "warehouseId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid"))
    })
    public QueryResult<StockBalanceQuery.Row> balances(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        var query = QueryHttpParameters.input(() -> {
            var parsed = new QueryHttpParameters(parameters).exact("id", UUID.class).exact("materialId", UUID.class)
                    .exact("warehouseId", UUID.class).exact("quantity", BigDecimal.class).range("quantity", BigDecimal.class).finish();
            return StockBalanceQuery.create(parsed.page(), parsed.pageSize(), parsed.sorts(), parsed.filters());
        });
        return queries.execute(query, QueryHttpParameters.context());
    }

    /**
     * 按真实来源、方向、UTC时间和数量读取历史流水。
     * @param parameters 白名单查询参数
     * @return 历史流水分页
     * @apiNote 示例：GET /api/erp/stock-movements?orderId={uuid}&direction=RECEIPT。
     */
    @GetMapping("/stock-movements")
    @Operation(summary = "查询库存流水", description = "queryCode=erp.stock.movement.list；ID/数量/发生时间可排序，默认时间倒序；时间仅支持UTC可表示的微秒精度，范围1000至9999年。")
    @Parameters({
        @Parameter(name = "page", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
        @Parameter(name = "pageSize", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "200")),
        @Parameter(name = "sort", in = ParameterIn.QUERY, array = @ArraySchema(schema = @Schema(type = "string", example = "quantity,asc"))),
        @Parameter(name = "id", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "quantity", in = ParameterIn.QUERY, schema = @Schema(type = "number", example = "0.125")),
        @Parameter(name = "quantityFrom", in = ParameterIn.QUERY, description = "闭区间下界，须同时提供quantityTo", schema = @Schema(type = "number", example = "0.125")),
        @Parameter(name = "quantityTo", in = ParameterIn.QUERY, description = "闭区间上界，须同时提供quantityFrom", schema = @Schema(type = "number", example = "2")),
        @Parameter(name = "balanceId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "orderId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "postingId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "orderLineId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
        @Parameter(name = "direction", in = ParameterIn.QUERY, schema = @Schema(type = "string", allowableValues = {"RECEIPT", "ISSUE"})),
        @Parameter(name = "occurredAt", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date-time", example = "2026-10-24T00:00:00.123456Z")),
        @Parameter(name = "occurredAtFrom", in = ParameterIn.QUERY, description = "闭区间下界，须同时提供occurredAtTo", schema = @Schema(type = "string", format = "date-time")),
        @Parameter(name = "occurredAtTo", in = ParameterIn.QUERY, description = "闭区间上界，须同时提供occurredAtFrom", schema = @Schema(type = "string", format = "date-time"))
    })
    public QueryResult<StockMovementQuery.Row> movements(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        var query = QueryHttpParameters.input(() -> {
            var parsed = new QueryHttpParameters(parameters).exact("id", UUID.class).exact("balanceId", UUID.class)
                    .exact("orderId", UUID.class).exact("postingId", UUID.class).exact("orderLineId", UUID.class)
                    .exact("direction", String.class).exact("quantity", BigDecimal.class).range("quantity", BigDecimal.class)
                    .exact("occurredAt", Instant.class).range("occurredAt", Instant.class).finish();
            return StockMovementQuery.create(parsed.page(), parsed.pageSize(), parsed.sorts(), parsed.filters());
        });
        return queries.execute(query, QueryHttpParameters.context());
    }
}
