package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.masterdata.MasterDataQuery;
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证主数据只读路由、白名单、参数绑定与分页稳定性。 */
class MasterDataQueryIntegrationTest extends MariaDbIntegrationTest {
    private final QueryGateway gateway;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final String prefix = UUID.randomUUID().toString();
    private final List<String> ids = new ArrayList<>();

    /**
     * 注入真实读取路由及独立夹具依赖。
     * @param gateway 查询入口
     * @param masters 主数据写仓储，仅用于夹具
     * @param unitOfWork 夹具事务
     * @param jdbc 清理客户端
     */
    @Autowired
    MasterDataQueryIntegrationTest(QueryGateway gateway, MasterDataRepository masters, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.gateway = gateway; this.masters = masters; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 相同编码可以跨类别存在，查询和ID详情均受必填类别约束。 */
    @Test
    void separatesKindsAndLoadsExactDetails() {
        for (var kind : MasterData.Kind.values()) create(kind, prefix, "同名");
        for (var kind : MasterData.Kind.values()) {
            var rows = read(kind, List.of(filter("code", QuerySpec.Operator.EQ, prefix))).getItems();
            assertEquals(1, rows.size());
            var row = rows.getFirst(); assertEquals(kind, row.kind()); assertEquals(0, row.version());
            assertEquals(kind == MasterData.Kind.MATERIAL ? "个" : null, row.baseUnit());
            assertEquals(row, read(kind, List.of(filter("id", QuerySpec.Operator.EQ, row.id()))).getItems().getFirst());
            var other = kind == MasterData.Kind.MATERIAL ? MasterData.Kind.CUSTOMER : MasterData.Kind.MATERIAL;
            assertTrue(read(other, List.of(filter("id", QuerySpec.Operator.EQ, row.id()))).getItems().isEmpty());
        }
        assertTrue(read(MasterData.Kind.MATERIAL, List.of(filter("id", QuerySpec.Operator.EQ, UUID.randomUUID()))).getItems().isEmpty());
    }

    /** 同名排序使用ID补齐，跨页没有遗漏或重复，空页仍保留总数。 */
    @Test
    void paginatesWithStableTieBreaker() {
        for (int index = 0; index < 3; index++) create(MasterData.Kind.MATERIAL, prefix + index, "同名");
        var filters = List.of(filter("code", QuerySpec.Operator.CONTAINS, prefix));
        var expected = ids.stream().sorted().toList();
        var actual = new ArrayList<String>();
        for (int page = 1; page <= 4; page++) {
            var result = gateway.execute(MasterDataQuery.create(MasterData.Kind.MATERIAL, page, 1, List.of("name,desc"), filters), QueryContext.anonymous(prefix));
            assertEquals(3, result.getTotal()); assertEquals(page, result.getPage());
            actual.addAll(result.getItems().stream().map(row -> row.id().toString()).toList());
        }
        assertEquals(expected, actual);
        assertEquals(200, MasterDataQuery.create(MasterData.Kind.MATERIAL, 1, 999, List.of(), filters).spec().getPageRequest().getPageSize());
    }

    /** 包含查询将百分号、下划线和反斜线视为字面字符，恶意SQL字符串只是参数。 */
    @Test
    void bindsLiteralSearchText() {
        create(MasterData.Kind.MATERIAL, prefix + "a", "折扣!%_\\商品");
        create(MasterData.Kind.MATERIAL, prefix + "b", "折扣XX商品");
        for (String text : List.of("!", "%", "_", "\\", "!%_\\")) {
            var result = read(MasterData.Kind.MATERIAL, List.of(filter("code", QuerySpec.Operator.CONTAINS, prefix), filter("name", QuerySpec.Operator.CONTAINS, text)));
            assertEquals(1, result.getTotal(), "字面搜索：" + text); assertEquals("折扣!%_\\商品", result.getItems().getFirst().name());
        }
        assertTrue(read(MasterData.Kind.MATERIAL, List.of(filter("name", QuerySpec.Operator.EQ, "' OR 1=1 --"))).getItems().isEmpty());
        assertTrue(read(MasterData.Kind.MATERIAL, List.of(filter("name", QuerySpec.Operator.CONTAINS, "' OR 1=1 --"))).getItems().isEmpty());
    }

    /** 非白名单字段、非法操作及错误类型在进入数据库前拒绝。 */
    @Test
    void rejectsUntrustedQueryShape() {
        assertThrows(IllegalArgumentException.class, () -> MasterDataQuery.create(MasterData.Kind.MATERIAL, 1, 20, List.of("name;drop table,asc"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> read(MasterData.Kind.MATERIAL, List.of(filter("warehouse", QuerySpec.Operator.EQ, "x"))));
        assertThrows(IllegalArgumentException.class, () -> read(MasterData.Kind.MATERIAL, List.of(filter("id", QuerySpec.Operator.EQ, "not-uuid"))));
        assertThrows(IllegalArgumentException.class, () -> read(MasterData.Kind.MATERIAL, List.of(filter("name", QuerySpec.Operator.BETWEEN, "a"))));
    }

    /** QueryGateway拒绝加入写事务，回滚夹具在之后查询中不可见。 */
    @Test
    void rejectsWriteTransactionAndDoesNotReadRolledBackRows() {
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> read(MasterData.Kind.MATERIAL, List.of())));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            create(MasterData.Kind.MATERIAL, prefix, "回滚"); throw new IllegalStateException("测试回滚");
        }));
        assertTrue(read(MasterData.Kind.MATERIAL, List.of(filter("code", QuerySpec.Operator.EQ, prefix))).getItems().isEmpty());
    }

    /** 仅清理本测试生成的主数据。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> { for (String id : ids) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id); return null; });
    }

    /**
     * 创建真实写模型夹具，不在查询模块写入数据库。
     * @param kind 类别
     * @param code 编码
     * @param name 名称
     */
    private void create(MasterData.Kind kind, String code, String name) {
        var value = MasterData.create(kind, code, name, kind == MasterData.Kind.MATERIAL ? "个" : null);
        ids.add(value.getId()); unitOfWork.execute(() -> { masters.save(value); return null; });
    }

    /**
     * 经统一入口执行主数据查询。
     * @param kind 类别
     * @param filters 输入条件
     * @return 有界分页结果
     */
    private QueryResult<MasterDataQuery.Row> read(MasterData.Kind kind, List<QuerySpec.FilterInput> filters) {
        return gateway.execute(MasterDataQuery.create(kind, 1, 20, List.of(), filters), QueryContext.anonymous(prefix));
    }

    /**
     * 创建单值过滤条件。
     * @param field 外部字段
     * @param operator 操作
     * @param value 参数
     * @return 待白名单验证的输入
     */
    private QuerySpec.FilterInput filter(String field, QuerySpec.Operator operator, Object value) {
        return new QuerySpec.FilterInput(field, operator, value, null);
    }
}
