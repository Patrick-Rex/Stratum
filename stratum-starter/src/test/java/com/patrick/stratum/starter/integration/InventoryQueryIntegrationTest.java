package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockBalance;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.domain.inventory.StockMovementRepository;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.inventory.StockBalanceQuery;
import com.patrick.stratum.query.inventory.StockMovementQuery;
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证库存读取的维度隔离、精确范围、排序及事务边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryQueryIntegrationTest extends MariaDbIntegrationTest {
    @LocalServerPort private int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private final QueryGateway gateway;
    private final StockBalanceRepository balances;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final StockMovementRepository movements;
    private final InventorySourceFixture sources;
    private final List<String> masterIds = new ArrayList<>();
    private final List<UUID> balanceIds = new ArrayList<>();
    private final QueryContext context = QueryContext.anonymous(UUID.randomUUID().toString());

    /**
     * 装配只读入口及测试夹具依赖。
     * @param gateway 查询入口
     * @param balances 余额夹具仓储
     * @param masters 主数据夹具仓储
     * @param unitOfWork 夹具事务
     * @param jdbc 清理客户端
     * @param movements 流水夹具仓储
     */
    @Autowired
    InventoryQueryIntegrationTest(QueryGateway gateway, StockBalanceRepository balances, MasterDataRepository masters,
            UnitOfWork unitOfWork, JdbcTemplate jdbc, StockMovementRepository movements) {
        this.gateway = gateway; this.balances = balances; this.masters = masters; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
        this.movements = movements; this.sources = new InventorySourceFixture(jdbc, unitOfWork);
    }

    /** 同物料不同仓库隔离，12位小数完整返回，ID详情和缺失维度都经统一入口。 */
    @Test
    void isolatesDimensionsAndPreservesDecimals() {
        UUID material = master(MasterData.Kind.MATERIAL); UUID warehouse = master(MasterData.Kind.WAREHOUSE);
        var first = balance(material, warehouse, "12345678901234567890.123456789012");
        balance(material, master(MasterData.Kind.WAREHOUSE), "2");
        var result = read(List.of(eq("materialId", material), eq("warehouseId", warehouse)));
        assertEquals(1, result.getTotal()); var row = result.getItems().getFirst();
        assertEquals(first.getQuantity().value(), row.quantity()); assertEquals(first.getId(), row.id()); assertEquals(0, row.version());
        assertEquals(row, read(List.of(eq("id", first.getId()))).getItems().getFirst());
        assertTrue(read(List.of(eq("warehouseId", UUID.randomUUID()))).getItems().isEmpty());
    }

    /** 数量闭区间包含两个端点，精确条件不做舍入，分页按数量和ID稳定排序。 */
    @Test
    void filtersInclusiveRangeAndPaginatesTies() {
        UUID warehouse = master(MasterData.Kind.WAREHOUSE);
        for (String quantity : List.of("0.125", "0.125", "0.25", "0.5")) balance(master(MasterData.Kind.MATERIAL), warehouse, quantity);
        var range = new QuerySpec.FilterInput("quantity", QuerySpec.Operator.BETWEEN, new BigDecimal("0.125"), new BigDecimal("0.25"));
        var filters = List.of(eq("warehouseId", warehouse), range);
        var first = gateway.execute(StockBalanceQuery.create(1, 2, List.of("quantity,asc"), filters), context);
        var second = gateway.execute(StockBalanceQuery.create(2, 2, List.of("quantity,asc"), filters), context);
        assertEquals(3, first.getTotal()); assertEquals(3, second.getTotal());
        assertEquals(balanceIds.subList(0, 2).stream().map(UUID::toString).sorted().toList(), first.getItems().stream().map(row -> row.id().toString()).toList());
        assertEquals(0, new BigDecimal("0.25").compareTo(second.getItems().getFirst().quantity()));
        assertEquals(2, read(List.of(eq("warehouseId", warehouse), eq("quantity", new BigDecimal("0.125")))).getTotal());
        assertTrue(read(List.of(eq("warehouseId", warehouse), eq("quantity", new BigDecimal("0.126")))).getItems().isEmpty());
    }

    /** 非法字段、字符串数量、未知操作和反向区间被白名单阻止。 */
    @Test
    void rejectsInvalidBalanceFilters() {
        for (var input : List.of(eq("quantity", "0"), eq("id", "' OR 1=1"), eq("unknown", UUID.randomUUID()),
                new QuerySpec.FilterInput("quantity", QuerySpec.Operator.BETWEEN, BigDecimal.ONE, BigDecimal.ZERO),
                new QuerySpec.FilterInput("quantity", QuerySpec.Operator.CONTAINS, BigDecimal.ONE, null))) {
            assertThrows(IllegalArgumentException.class, () -> read(List.of(input)));
        }
        assertThrows(IllegalArgumentException.class, () -> StockBalanceQuery.create(1, 20, List.of("quantity;drop,asc"), List.of()));
    }

    /** 写事务内拒绝查询，回滚余额不出现在之后的列表中。 */
    @Test
    void rejectsWriteTransactionsAndHidesRolledBackBalance() {
        UUID material = master(MasterData.Kind.MATERIAL); UUID warehouse = master(MasterData.Kind.WAREHOUSE);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> read(List.of(eq("warehouseId", warehouse)))));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            balance(material, warehouse, "1"); throw new IllegalStateException("测试回滚");
        }));
        assertTrue(read(List.of(eq("warehouseId", warehouse))).getItems().isEmpty());
    }

    /** 流水按真实余额和来源筛选，出入库历史数量、版本及UTC微秒均无损。 */
    @Test
    void readsExactMovementSourceAndHistoricalSnapshot() {
        var balance = balance(master(MasterData.Kind.MATERIAL), master(MasterData.Kind.WAREHOUSE), "10");
        var receipt = movement(balance, StockMovement.Direction.RECEIPT, "0.123456789012", Instant.parse("2026-10-24T00:00:01.123456Z"));
        var issue = movement(balance, StockMovement.Direction.ISSUE, "0.1", Instant.parse("2026-10-24T00:00:02Z"));
        for (var source : List.of(eq("id", receipt.getId()), eq("orderId", receipt.getSource().orderId()),
                eq("postingId", receipt.getSource().postingId()), eq("orderLineId", receipt.getSource().orderLineId()))) {
            var result = readMovements(List.of(eq("balanceId", balance.getId()), source));
            assertEquals(1, result.getTotal()); var row = result.getItems().getFirst();
            assertEquals(receipt.getId(), row.id()); assertEquals(receipt.getQuantity().value(), row.quantity());
            assertEquals(receipt.getBalanceAfter().value(), row.balanceAfter()); assertEquals(receipt.getBalanceVersion(), row.balanceVersion());
            assertEquals(receipt.getOccurredAt(), row.occurredAt());
        }
        assertEquals(issue.getId(), readMovements(List.of(eq("balanceId", balance.getId()), eq("direction", "ISSUE"))).getItems().getFirst().id());
        assertEquals(List.of(issue.getId(), receipt.getId()), readMovements(List.of(eq("balanceId", balance.getId()))).getItems().stream().map(StockMovementQuery.Row::id).toList());
        assertTrue(readMovements(List.of(eq("postingId", UUID.randomUUID()))).getItems().isEmpty());
    }

    /** 时间区间和数量区间均包含边界，同一时间的跨页顺序由ID稳定补齐。 */
    @Test
    void filtersMovementTimeAndQuantityRangesWithStablePaging() {
        var balance = balance(master(MasterData.Kind.MATERIAL), master(MasterData.Kind.WAREHOUSE), "10");
        Instant firstTime = Instant.parse("2026-10-24T00:00:01Z"); Instant lastTime = Instant.parse("2026-10-24T00:00:02Z");
        var first = movement(balance, StockMovement.Direction.RECEIPT, "0.1", firstTime);
        var second = movement(balance, StockMovement.Direction.RECEIPT, "0.1", firstTime);
        var last = movement(balance, StockMovement.Direction.RECEIPT, "0.2", lastTime);
        var filters = List.of(eq("balanceId", balance.getId()),
                new QuerySpec.FilterInput("occurredAt", QuerySpec.Operator.BETWEEN, firstTime, lastTime),
                new QuerySpec.FilterInput("quantity", QuerySpec.Operator.BETWEEN, new BigDecimal("0.1"), new BigDecimal("0.2")));
        var ids = new ArrayList<String>();
        for (int page = 1; page <= 3; page++) {
            var result = gateway.execute(StockMovementQuery.create(page, 1, List.of("occurredAt,asc"), filters), context);
            assertEquals(3, result.getTotal()); ids.add(result.getItems().getFirst().id().toString());
        }
        var expected = new ArrayList<>(List.of(first.getId().toString(), second.getId().toString()).stream().sorted().toList());
        expected.add(last.getId().toString()); assertEquals(expected, ids);
        assertEquals(2, readMovements(List.of(eq("balanceId", balance.getId()), eq("occurredAt", firstTime), eq("quantity", new BigDecimal("0.1")))).getTotal());
    }

    /** 非法方向、来源类型、时间倒置及不可排序字段被拒绝，流水读取也不能加入写事务。 */
    @Test
    void rejectsInvalidMovementQueriesAndWriteTransaction() {
        for (var input : List.of(eq("direction", "UNKNOWN"), eq("postingId", "' OR 1=1"),
                new QuerySpec.FilterInput("occurredAt", QuerySpec.Operator.BETWEEN, Instant.parse("2026-10-24T00:00:02Z"), Instant.parse("2026-10-24T00:00:01Z")))) {
            assertThrows(IllegalArgumentException.class, () -> readMovements(List.of(input)));
        }
        assertThrows(IllegalArgumentException.class, () -> StockMovementQuery.create(1, 20, List.of("direction,asc"), List.of()));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> readMovements(List.of())));
    }

    /** HTTP保留精确数量、来源和UTC微秒，并支持闭区间及重复排序。
     * @throws Exception HTTP失败
     */
    @Test
    void readsInventoryOverHttpWithExactSourceAndRanges() throws Exception {
        UUID material = master(MasterData.Kind.MATERIAL); UUID warehouse = master(MasterData.Kind.WAREHOUSE);
        var balance = balance(material, warehouse, "0.125");
        var receipt = movement(balance, StockMovement.Direction.RECEIPT, "0.123456789012", Instant.parse("2026-10-24T00:00:01.123456Z"));
        var balances = http("/api/erp/stock-balances?materialId=" + material + "&warehouseId=" + warehouse
                + "&quantityFrom=0.2&quantityTo=0.3&sort=quantity,asc&sort=id,asc", 200).path("data");
        assertEquals(1, balances.path("total").asInt());
        assertEquals(0, balance.getQuantity().value().compareTo(new BigDecimal(balances.path("items").get(0).path("quantity").asText())));
        String source = "&orderId=" + receipt.getSource().orderId() + "&postingId=" + receipt.getSource().postingId()
                + "&orderLineId=" + receipt.getSource().orderLineId();
        var result = http("/api/erp/stock-movements?balanceId=" + balance.getId() + source
                + "&direction=RECEIPT&quantityFrom=0.123456789012&quantityTo=0.123456789012"
                + "&occurredAtFrom=2026-10-24T00:00:01.123456Z&occurredAtTo=2026-10-24T00:00:01.123456Z", 200).path("data");
        assertEquals(1, result.path("total").asInt()); var row = result.path("items").get(0);
        assertEquals(receipt.getId().toString(), row.path("id").asText());
        assertEquals(receipt.getOccurredAt().toString(), row.path("occurredAt").asText());
        assertEquals(receipt.getSource().postingId().toString(), row.path("postingId").asText());
        assertEquals(0, receipt.getQuantity().value().compareTo(new BigDecimal(row.path("quantity").asText())));
        assertEquals(0, http("/api/erp/stock-movements?postingId=" + UUID.randomUUID(), 200).path("data").path("total").asInt());
        assertEquals(1, http("/api/erp/stock-movements?id=" + receipt.getId()
                + "&quantity=0.123456789012&occurredAt=2026-10-24T00:00:01.123456Z", 200).path("data").path("total").asInt());
    }

    /** 时间、数量及区间两端在HTTP边界受限，未知/单边/反向条件不能进入JDBC。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsHttpScalarAndRangeViolations() throws Exception {
        for (String query : List.of("quantity=NaN", "quantity=1e2147483647", "quantity=1e-2147483647", "quantityFrom=0",
                "quantityTo=1", "quantityFrom=2&quantityTo=1", "quantityFrom=0&quantityTo=1e512",
                "occurredAt=not-a-time", "occurredAt=2026-10-24T00:00:00.123456789Z", "occurredAt=0999-12-31T23:59:59Z",
                "occurredAtFrom=2026-10-24T00:00:00Z&occurredAtTo=2026-10-24T00:00:00.000000001Z",
                "occurredAtFrom=2026-10-24T00:00:01Z&occurredAtTo=2026-10-24T00:00:00Z",
                "direction=INVALID", "sort=postingId,asc", "postingId=invalid", "unknown=x")) {
            assertEquals(40000, http("/api/erp/stock-movements?" + query, 400).path("code").asInt());
        }
        assertEquals(40000, http("/api/erp/stock-balances?quantity=1e512", 400).path("code").asInt());
    }

    /** OpenAPI列出两类端点的公共参数及各自维度，隐藏原始参数容器。
     * @throws Exception HTTP失败
     */
    @Test
    void documentsInventoryQueryParameters() throws Exception {
        var document = http("/v3/api-docs", 200);
        for (String path : List.of("/api/erp/stock-balances", "/api/erp/stock-movements")) {
            var operation = document.path("paths").path(path).path("get");
            assertTrue(operation.path("responses").has("200"));
            var names = new java.util.HashSet<String>();
            for (var parameter : operation.path("parameters")) names.add(parameter.path("name").asText());
            assertTrue(names.containsAll(List.of("page", "pageSize", "sort", "id", "quantity", "quantityFrom", "quantityTo")));
            assertFalse(names.contains("parameters"));
            if (path.endsWith("balances")) assertTrue(names.containsAll(List.of("materialId", "warehouseId")));
            else assertTrue(names.containsAll(List.of("postingId", "orderId", "orderLineId", "occurredAt", "occurredAtFrom", "occurredAtTo", "direction")));
        }
    }

    /**
     * 使用当前测试关联标识发送GET，关闭客户端并核对HTTP状态。
     * @param path 路径及查询参数
     * @param status 预期状态
     * @return JSON响应
     * @throws Exception HTTP失败
     */
    private JsonNode http(String path, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("X-Trace-Id", context.traceId()).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(status, response.statusCode(), response.body());
            assertEquals(context.traceId(), response.headers().firstValue("X-Trace-Id").orElseThrow());
            var body = json.readTree(response.body());
            if (path.startsWith("/api/")) assertEquals(context.traceId(), body.path("traceId").asText());
            return body;
        }
    }

    /** 仅清理本测试余额和主数据。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            for (UUID id : balanceIds) jdbc.update("DELETE FROM erp_stock_movement WHERE balance_id = ?", id.toString());
            for (UUID id : balanceIds) jdbc.update("DELETE FROM erp_stock_balance WHERE id = ?", id.toString());
            sources.clean();
            for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            return null;
        });
    }

    /**
     * 创建库存夹具，所有写入均在查询模块外的工作单元内。
     * @param material 物料
     * @param warehouse 仓库
     * @param quantity 数量文本
     * @return 已保存余额
     */
    private StockBalance balance(UUID material, UUID warehouse, String quantity) {
        var balance = StockBalance.open(material, warehouse); balance.receive(new Quantity(new BigDecimal(quantity)));
        balanceIds.add(balance.getId()); unitOfWork.execute(() -> { balances.save(balance); return null; }); return balance;
    }

    /**
     * 创建独立主数据夹具。
     * @param kind 类别
     * @return 标识
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "库存查询", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId()); unitOfWork.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 执行默认分页余额读取。
     * @param filters 过滤表达
     * @return 分页结果
     */
    private QueryResult<StockBalanceQuery.Row> read(List<QuerySpec.FilterInput> filters) {
        return gateway.execute(StockBalanceQuery.create(1, 20, List.of(), filters), context);
    }

    /**
     * 经统一入口读取默认最新优先的流水分页。
     * @param filters 条件
     * @return 流水结果
     */
    private QueryResult<StockMovementQuery.Row> readMovements(List<QuerySpec.FilterInput> filters) {
        return gateway.execute(StockMovementQuery.create(1, 20, List.of(), filters), context);
    }

    /**
     * 创建带真实来源的指定时间流水夹具，余额及流水同事务写入。
     * @param balance 已保存余额
     * @param direction 方向
     * @param quantity 数量文本
     * @param occurredAt 确定的UTC时间
     * @return 流水快照
     */
    private StockMovement movement(StockBalance balance, StockMovement.Direction direction, String quantity, Instant occurredAt) {
        var source = new StockMovement.Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var amount = new Quantity(new BigDecimal(quantity));
        sources.prepare(source, balance.getMaterialId(), balance.getWarehouseId(), direction, amount);
        return unitOfWork.execute(() -> {
            if (direction == StockMovement.Direction.RECEIPT) balance.receive(amount); else balance.issue(amount);
            balances.save(balance);
            var movement = StockMovement.restore(UUID.randomUUID(), balance.getId(), source, direction, amount,
                    balance.getQuantity(), balance.getVersion(), occurredAt);
            movements.append(movement); return movement;
        });
    }

    /**
     * 创建单值精确过滤输入。
     * @param field 字段
     * @param value 值
     * @return 未解析过滤表达
     */
    private QuerySpec.FilterInput eq(String field, Object value) { return new QuerySpec.FilterInput(field, QuerySpec.Operator.EQ, value, null); }
}
