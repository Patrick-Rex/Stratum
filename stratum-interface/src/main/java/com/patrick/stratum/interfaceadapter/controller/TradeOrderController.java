package com.patrick.stratum.interfaceadapter.controller;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.command.CommandGateway;
import com.patrick.stratum.application.trade.CreateOrderCommand;
import com.patrick.stratum.application.trade.FulfillOrderCommand;
import com.patrick.stratum.application.trade.FulfillmentResult;
import com.patrick.stratum.application.trade.OrderResult;
import com.patrick.stratum.domain.trade.TradeOrder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 采购销售HTTP写适配，只构造服务端主体命令，不在端点计算金额或修改库存。 */
@RestController
@RequestMapping(path = "/api/erp/orders/{kind}", version = "1")
@RequiredArgsConstructor
public class TradeOrderController {
    private static final String IDEMPOTENCY_PATTERN = "[A-Za-z0-9._:-]{1,128}";
    private final CommandGateway commands;
    private final CurrentIdentity identities;

    /**
     * 固定订单头及有界明细输入。
     * @param partyId 采购供应商或销售客户
     * @param warehouseId 固定仓库
     * @param lines 固定明细，保持提交顺序
     */
    @Schema(name = "CreateTradeOrderRequest")
    public record CreateRequest(@NotNull UUID partyId, @NotNull UUID warehouseId,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid CreateLine> lines) { }

    /**
     * 单行输入边界，金额及累计履约规则由领域模型负责。
     * @param materialId 物料
     * @param quantity 正数量，最多26位整数和12位小数
     * @param unitPrice 非负人民币单价，最多13位整数和6位小数
     */
    @Schema(name = "CreateTradeOrderLine")
    public record CreateLine(@NotNull UUID materialId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("99999999999999999999999999.999999999999")
            @Digits(integer = 26, fraction = 12) @Schema(example = "1.25") BigDecimal quantity,
            @NotNull @DecimalMin("0") @DecimalMax("9999999999999.999999")
            @Digits(integer = 13, fraction = 6) @Schema(example = "12.3456") BigDecimal unitPrice) { }

    /**
     * 本次履约输入，只接受订单行标识及数量，不能改写物料或仓库。
     * @param version 观察版本
     * @param lines 本次分批明细
     */
    @Schema(name = "FulfillTradeOrderRequest")
    public record FulfillRequest(@NotNull @PositiveOrZero @Schema(example = "0") Long version,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid FulfillLine> lines) { }

    /**
     * 一条分批履约输入。
     * @param orderLineId 原订单行
     * @param quantity 本次正数量
     */
    @Schema(name = "FulfillTradeOrderLine")
    public record FulfillLine(@NotNull UUID orderLineId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("99999999999999999999999999.999999999999")
            @Digits(integer = 26, fraction = 12) @Schema(example = "0.25") BigDecimal quantity) { }

    /**
     * 创建采购或销售订单，命令层负责引用、金额、事件及幂等。
     * @param kind PURCHASE或SALE
     * @param key 客户端幂等键
     * @param request 已验证的固定订单输入
     * @return 201、Location及订单快照
     * @apiNote 示例：POST /api/erp/orders/PURCHASE，包含partyId、warehouseId、lines并携带Idempotency-Key。
     */
    @PostMapping
    @Operation(summary = "创建采购或销售订单", description = "自动单号；明细创建后固定；人民币金额按精确乘积汇总后四舍五入到分。主体来自服务器验证的Access身份。")
    @ApiResponse(responseCode = "201", description = "创建或原请求重放成功，Location指向订单")
    public ResponseEntity<OrderResult> create(@PathVariable("kind") TradeOrder.Kind kind,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String key,
            @Valid @RequestBody CreateRequest request) {
        var lines = request.lines().stream().map(line -> CreateOrderCommand.Line.builder().materialId(line.materialId())
                .quantity(line.quantity()).unitPrice(line.unitPrice()).build()).toList();
        var result = commands.execute(CreateOrderCommand.builder().subjectId(identities.require().getName()).idempotencyKey(key).kind(kind)
                .partyId(request.partyId()).warehouseId(request.warehouseId()).lines(lines).build()).data();
        return ResponseEntity.created(URI.create("/api/erp/orders/" + kind + "/" + result.getId())).body(result);
    }

    /**
     * 分批收货或出库，领域与应用层完成剩余量、库存、事务和幂等校验。
     * @param kind 采购或销售类别
     * @param id 原订单UUID
     * @param key 幂等键
     * @param request 观察版本及本次明细
     * @return 201及固定履约结果，Location定位该批历史查询
     * @apiNote 示例：POST /api/erp/orders/PURCHASE/{id}/fulfillments，含version和lines。
     */
    @PostMapping("/{id}/fulfillments")
    @Operation(summary = "分批收货或出库", description = "采购增加库存，销售扣减库存；不允许负库存或超订单数量。同键重放原批次及当时订单快照，观察版本过期返回409。")
    @ApiResponse(responseCode = "201", description = "履约或原请求重放成功，Location按postingId过滤本批履约历史")
    public ResponseEntity<FulfillmentResult> fulfill(@PathVariable("kind") TradeOrder.Kind kind, @PathVariable("id") UUID id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String key,
            @Valid @RequestBody FulfillRequest request) {
        var lines = request.lines().stream().map(line -> FulfillOrderCommand.Line.builder()
                .orderLineId(line.orderLineId()).quantity(line.quantity()).build()).toList();
        var result = commands.execute(FulfillOrderCommand.builder().subjectId(identities.require().getName()).idempotencyKey(key).kind(kind)
                .orderId(id).version(request.version()).lines(lines).build()).data();
        return ResponseEntity.created(URI.create("/api/erp/orders/" + kind + "/" + id + "/fulfillments?postingId=" + result.getPostingId())).body(result);
    }
}
