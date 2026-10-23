package com.patrick.stratum.application.trade;

import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.event.OutboxEventWriter;
import com.patrick.stratum.application.inventory.StockPosting;
import com.patrick.stratum.application.inventory.StockPostingApplicationService;
import com.patrick.stratum.application.service.IdempotentApplicationService;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.helper.ValidationHelper;
import com.patrick.stratum.domain.idempotency.IdempotencyReceipt;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.OrderChanged;
import com.patrick.stratum.domain.trade.Fulfillment;
import com.patrick.stratum.domain.trade.FulfillmentRepository;
import com.patrick.stratum.domain.trade.OrderLine;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.domain.trade.TradeOrderRepository;
import com.patrick.stratum.domain.trade.UnitPrice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 采购销售写用例编排，通过领域聚合、幂等回执及Outbox实现同事务提交。 */
@Component
@RequiredArgsConstructor
public class TradeOrderApplicationService {
    private final TradeOrderRepository orders;
    private final MasterDataRepository masters;
    private final IdempotentApplicationService idempotency;
    private final OutboxEventWriter events;
    private final FulfillmentRepository fulfillments;
    private final StockPostingApplicationService inventory;

    /**
     * 验证主数据引用并创建订单，重复请求重放原始订单及明细标识。
     * @param command 主体由服务器提供的创建命令
     * @return 创建或重放的订单快照
     * @throws StratumException 引用不存在、类别不符或幂等请求冲突
     * @apiNote 示例：gateway.execute(CreateOrderCommand.builder()...build())。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public OrderResult create(CreateOrderCommand command) {
        Objects.requireNonNull(command, "command");
        var key = key("create", command.getKind(), command.getSubjectId(), command.getIdempotencyKey());
        return idempotency.execute(key, command, OrderResult.class, () -> {
            requireMaster(command.getPartyId(), command.getKind() == TradeOrder.Kind.PURCHASE ? MasterData.Kind.SUPPLIER : MasterData.Kind.CUSTOMER);
            requireMaster(command.getWarehouseId(), MasterData.Kind.WAREHOUSE);
            var validated = new HashSet<UUID>();
            for (var line : command.getLines()) {
                if (validated.add(line.getMaterialId())) requireMaster(line.getMaterialId(), MasterData.Kind.MATERIAL);
            }
            var order = newOrder(command);
            orders.save(order);
            events.append(new OrderChanged(order, OrderChanged.Change.CREATED), "erp." + order.getKind().name().toLowerCase(Locale.ROOT) + ".created");
            return OrderResult.from(order);
        });
    }

    /**
     * 在纯领域构造阶段转换已知输入错误，不捕获仓储、事件或幂等实现故障。
     * @param command 创建命令
     * @return 新订单聚合
     * @throws StratumException 数量、单价或总金额违反输入边界
     */
    private TradeOrder newOrder(CreateOrderCommand command) {
        try {
            var lines = command.getLines().stream().map(line -> OrderLine.create(line.getMaterialId(),
                    new Quantity(line.getQuantity()), new UnitPrice(line.getUnitPrice()))).toList();
            return TradeOrder.create(command.getKind(), command.getPartyId(), command.getWarehouseId(), lines);
        } catch (IllegalArgumentException invalidInput) {
            throw new StratumException(CommonErrorCode.BAD_REQUEST);
        }
    }

    /**
     * 按观察版本履约，订单、凭据、库存流水、事件和幂等回执同事务提交。
     * @param command 服务器主体、订单版本及本次行数量
     * @return 本次提交或原请求重放的固定结果
     * @throws StratumException 来源不存在、版本/数量冲突或库存不足
     * @apiNote 示例：gateway.execute(FulfillOrderCommand.builder()...build())。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public FulfillmentResult fulfill(FulfillOrderCommand command) {
        Objects.requireNonNull(command, "command");
        var key = key("fulfill", command.getKind(), command.getSubjectId(), command.getIdempotencyKey());
        return idempotency.execute(key, command, FulfillmentResult.class, () -> {
            var order = orders.findById(Objects.requireNonNull(command.getOrderId(), "orderId"))
                    .orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
            if (order.getKind() != command.getKind()) throw new StratumException(CommonErrorCode.NOT_FOUND);
            order.requireVersion(command.getVersion());
            var quantities = new LinkedHashMap<UUID, Quantity>();
            for (var line : command.getLines()) {
                var id = Objects.requireNonNull(line.getOrderLineId(), "orderLineId");
                if (quantities.putIfAbsent(id, new Quantity(line.getQuantity()).requirePositive()) != null) {
                    throw new StratumException(CommonErrorCode.BAD_REQUEST);
                }
            }
            order.fulfill(quantities);
            orders.save(order);
            var fulfillment = Fulfillment.create(order, quantities);
            fulfillments.append(fulfillment);
            var direction = order.getKind() == TradeOrder.Kind.PURCHASE ? StockMovement.Direction.RECEIPT : StockMovement.Direction.ISSUE;
            order.getLines().stream().filter(line -> quantities.containsKey(line.getId()))
                    .sorted(Comparator.comparing(OrderLine::getMaterialId).thenComparing(OrderLine::getId))
                    .forEach(line -> inventory.post(StockPosting.builder().materialId(line.getMaterialId())
                            .warehouseId(order.getWarehouseId()).source(new StockMovement.Source(order.getId(), fulfillment.getId(), line.getId()))
                            .direction(direction).quantity(quantities.get(line.getId())).build()));
            events.append(new OrderChanged(order, OrderChanged.Change.FULFILLED), "erp." + order.getKind().name().toLowerCase(Locale.ROOT) + ".fulfilled");
            return new FulfillmentResult(fulfillment.getId(), OrderResult.from(order));
        });
    }

    /**
     * 校验写用例引用，数据库外键继续防护删除竞争。
     * @param id 主数据标识
     * @param kind 必须匹配的类别
     */
    private void requireMaster(UUID id, MasterData.Kind kind) {
        Objects.requireNonNull(id, "masterId");
        masters.findById(id.toString()).orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND)).requireKind(kind);
    }

    /**
     * 使用服务端操作、版本、类别和主体隔离客户端幂等键。
     * @param operation 操作代码
     * @param kind 订单类别
     * @param subjectId 服务器主体
     * @param requestKey 客户端键
     * @return 固定作用域键
     */
    private IdempotencyReceipt.Key key(String operation, TradeOrder.Kind kind, String subjectId, String requestKey) {
        Objects.requireNonNull(kind, "kind");
        String subject = ValidationHelper.requireNonBlank(subjectId, "subjectId").trim();
        if (!subject.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("主体标识格式不正确");
        return new IdempotencyReceipt.Key("trade:" + operation + ":v1:" + kind.name() + ":" + subject, requestKey);
    }
}
