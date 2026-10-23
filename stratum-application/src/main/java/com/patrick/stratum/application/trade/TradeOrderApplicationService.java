package com.patrick.stratum.application.trade;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.event.OutboxEventWriter;
import com.patrick.stratum.application.service.IdempotentApplicationService;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.helper.ValidationHelper;
import com.patrick.stratum.domain.idempotency.IdempotencyReceipt;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.OrderChanged;
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
            var lines = command.getLines().stream().map(line -> {
                if (validated.add(line.getMaterialId())) requireMaster(line.getMaterialId(), MasterData.Kind.MATERIAL);
                return OrderLine.create(line.getMaterialId(), new Quantity(line.getQuantity()), new UnitPrice(line.getUnitPrice()));
            }).toList();
            var order = TradeOrder.create(command.getKind(), command.getPartyId(), command.getWarehouseId(), lines);
            orders.save(order);
            events.append(new OrderChanged(order, OrderChanged.Change.CREATED), "erp." + order.getKind().name().toLowerCase(Locale.ROOT) + ".created");
            return OrderResult.from(order);
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
