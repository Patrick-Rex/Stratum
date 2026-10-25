package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockBalance;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.domain.inventory.StockMovementRepository;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 库存余额与流水共用精确库存读取权限，真实HTTP验证授权即时生效且不接受自报主体。 */
class InventoryAuthorizationHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private MasterDataRepository masters;
    @Autowired private StockBalanceRepository balances;
    @Autowired private StockMovementRepository movements;
    @Autowired private UnitOfWork work;
    private final List<String> masterIds = new ArrayList<>();
    private InventorySourceFixture sources;
    private StockBalance balance;
    private StockMovement movement;
    private UUID role;
    private long roleVersion;
    private String token;

    /** 清理本测试库存及关联来源，父夹具清理自己创建的账号与角色。 */
    @AfterEach
    void cleanBusiness() {
        work.execute(() -> {
            if (balance != null) {
                jdbc.update("DELETE FROM erp_stock_movement WHERE balance_id=?", balance.getId().toString());
                jdbc.update("DELETE FROM erp_stock_balance WHERE id=?", balance.getId().toString());
            }
            if (sources != null) sources.clean();
            for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id=?", id);
            return null;
        });
    }

    /**
     * 无授权和仅库存FEAT均403；授权后返回真实余额流水，撤回后同一Access立即失去访问权。
     * @throws Exception HTTP失败
     */
    @Test
    void grantsAndRevokesActualBalanceAndMovementReads() throws Exception {
        prepare();
        var paths = List.of("/api/erp/stock-balances?id=" + balance.getId(), "/api/erp/stock-movements?balanceId=" + balance.getId());
        for (String path : paths) assertEquals(403, request("GET", path, token, null).statusCode());
        grant(Set.of(PermissionCatalog.INVENTORY_FEATURE.permission()));
        for (String path : paths) assertEquals(403, request("GET", path, token, null).statusCode());
        grant(Set.of(PermissionCatalog.INVENTORY_READ.permission()));
        var actualBalance = data(request("GET", paths.get(0), token, null), 200);
        assertEquals(1, actualBalance.path("total").asLong());
        assertEquals(balance.getId().toString(), actualBalance.path("items").get(0).path("id").asText());
        assertEquals(0, BigDecimal.ONE.compareTo(new BigDecimal(actualBalance.path("items").get(0).path("quantity").asText())));
        var actualMovement = data(request("GET", paths.get(1), token, null), 200);
        assertEquals(1, actualMovement.path("total").asLong());
        assertEquals(movement.getId().toString(), actualMovement.path("items").get(0).path("id").asText());
        assertEquals("RECEIPT", actualMovement.path("items").get(0).path("direction").asText());
        assertTrue(data(request("GET", "/auth/me", token, null), 200).path("featurePermissions").isEmpty());
        grant(Set.of());
        for (String path : paths) {
            var rejected = request("GET", path, token, null); assertEquals(403, rejected.statusCode());
            assertEquals(40300, json.readTree(rejected.body()).path("code").asInt());
            assertTrue(json.readTree(rejected.body()).path("data").isNull());
            assertEquals(1, data(request("GET", path, accessToken(), null), 200).path("total").asLong());
        }
    }

    /**
     * 其他API和功能权限不能替代库存权限；匿名与Refresh保持401，未授权不进入过滤参数解析。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsOtherPermissionsAndUntrustedIdentity() throws Exception {
        prepare();
        grant(PermissionCatalog.permissions().stream().filter(permission -> !permission.equals(PermissionCatalog.INVENTORY_READ.permission())).collect(Collectors.toSet()));
        for (String path : List.of("/api/erp/stock-balances", "/api/erp/stock-movements")) {
            assertEquals(403, request("GET", path, token, null).statusCode());
            assertEquals(403, request("GET", path + "?unknown=1", token, null).statusCode());
            assertEquals(401, request("GET", path, null, null).statusCode());
            assertEquals(401, request("GET", path, refreshToken(), null).statusCode());
        }
    }

    /**
     * 准备真实一单位库存及关系来源，并给普通账号绑定空API权限、显式ALL仓库范围角色。
     * @throws Exception HTTP失败
     */
    private void prepare() throws Exception {
        sources = new InventorySourceFixture(jdbc, work);
        var material = master(MasterData.Kind.MATERIAL); var warehouse = master(MasterData.Kind.WAREHOUSE);
        balance = StockBalance.open(material, warehouse); balance.receive(new Quantity(BigDecimal.ONE));
        var source = new StockMovement.Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        sources.prepare(source, material, warehouse, StockMovement.Direction.RECEIPT, new Quantity(BigDecimal.ONE));
        work.execute(() -> {
            balances.save(balance);
            movement = StockMovement.restore(UUID.randomUUID(), balance.getId(), source, StockMovement.Direction.RECEIPT,
                    new Quantity(BigDecimal.ONE), balance.getQuantity(), balance.getVersion(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            movements.append(movement); return null;
        });
        String username = "stock-rbac-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password); role = createRole("stock-rbac-" + UUID.randomUUID(), "库存权限");
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", Set.of(role))), 200);
        data(request("PUT", "/api/auth/roles/" + role + "/warehouse-scope", accessToken(),
                Map.of("version", 0, "scopeType", "ALL", "warehouseIds", Set.of())), 200);
        token = login(username, password).accessToken();
    }

    /**
     * 保存本测试主数据并记录清理范围。
     * @param kind 物料或仓库
     * @return 主数据UUID
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "库存鉴权", kind == MasterData.Kind.MATERIAL ? "件" : null);
        masterIds.add(value.getId()); work.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 通过管理员HTTP配置角色，不更换普通账号会话。
     * @param grants 新权限集合
     * @throws Exception HTTP失败
     */
    private void grant(Set<Permission> grants) throws Exception {
        roleVersion = data(request("PUT", "/api/auth/roles/" + role, accessToken(),
                Map.of("version", roleVersion, "name", "库存权限", "permissions", grants)), 200).path("version").asLong();
    }
}
