package com.patrick.stratum.starter.integration;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.patrick.stratum.application.event.OutboxRelayControlApplicationService;
import com.patrick.stratum.domain.event.OutboxRelayControlRepository.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 专用测试库中通过真实认证验证Relay共享状态管理HTTP协议。 */
class OutboxRelayControlHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    private static final String PATH = "/api/management/outbox/relay";
    @Autowired private OutboxRelayControlApplicationService control;
    private State initial;

    /** 记录测试开始时的暂停状态，以递增版本恢复而不覆盖其他状态。 */
    @BeforeEach
    void rememberState() { initial = control.current(); }

    /** 仅在专用测试库中恢复暂停标志，不回退共享版本。 */
    @AfterEach
    void restoreState() {
        if (initial != null) {
            State current = control.current();
            if (current.paused() != initial.paused()) control.change(current.revision(), initial.paused());
        }
    }

    /**
     * 管理员按观察版本修改，重复当前目标不递增，旧版本不可覆盖。
     * @throws Exception HTTP通信失败
     */
    @Test
    void updatesOnlyTheObservedVersion() throws Exception {
        var first = request("GET", PATH, accessToken(), null);
        var state = data(first, 200); long revision = state.path("revision").asLong(); boolean paused = state.path("paused").asBoolean();
        assertEquals(initial.revision(), revision); assertEquals(initial.paused(), paused);
        assertTrue(first.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        var changedResponse = request("PUT", PATH, accessToken(), Map.of("revision", revision, "paused", !paused));
        var changed = data(changedResponse, 200);
        assertEquals(revision + 1, changed.path("revision").asLong()); assertEquals(!paused, changed.path("paused").asBoolean());
        assertTrue(changedResponse.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals(changed, data(request("PUT", PATH, accessToken(), Map.of("revision", revision + 1, "paused", !paused)), 200));
        assertEquals(409, request("PUT", PATH, accessToken(), Map.of("revision", revision, "paused", paused)).statusCode());
        assertEquals(changed, data(request("GET", PATH, accessToken(), null), 200));
    }

    /**
     * 匿名、Refresh、普通账号和已撤权管理员均不能读取或变更。
     * @throws Exception HTTP通信失败
     */
    @Test
    void rechecksRealIdentityAndCurrentAdministrator() throws Exception {
        String username = "relay-http-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password); var ordinary = login(username, password);
        assertEquals(401, request("GET", PATH, null, null).statusCode());
        assertEquals(401, request("PUT", PATH, refreshToken(), Map.of("revision", initial.revision(), "paused", !initial.paused())).statusCode());
        assertEquals(403, request("GET", PATH, ordinary.accessToken(), null).statusCode());
        assertEquals(403, request("PUT", PATH, ordinary.accessToken(), Map.of("revision", initial.revision(), "paused", !initial.paused())).statusCode());
        String adminRole = jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class);
        assertEquals(200, request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", List.of(adminRole))).statusCode());
        var promoted = login(username, password);
        assertEquals(200, request("GET", PATH, promoted.accessToken(), null).statusCode());
        assertEquals(200, request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 1, "roleIds", List.of())).statusCode());
        assertEquals(401, request("GET", PATH, promoted.accessToken(), null).statusCode());
        assertEquals(403, request("PUT", PATH, login(username, password).accessToken(), Map.of("revision", initial.revision(), "paused", initial.paused())).statusCode());
        assertEquals(initial, control.current());
    }

    /**
     * 缺字段、非法版本及未知接口版本拒绝，OpenAPI包含两个操作。
     * @throws Exception HTTP通信失败
     */
    @Test
    void validatesInputAndDocumentsBothOperations() throws Exception {
        assertEquals(400, request("PUT", PATH, accessToken(), Map.of("paused", true)).statusCode());
        assertEquals(400, request("PUT", PATH, accessToken(), Map.of("revision", initial.revision())).statusCode());
        assertEquals(400, request("PUT", PATH, accessToken(), Map.of("revision", -1, "paused", true)).statusCode());
        assertEquals(400, request("GET", PATH, accessToken(), null, "2").statusCode());
        assertEquals(initial, control.current());
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(endpoint("/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode()); var path = json.readTree(response.body()).path("paths").path(PATH);
            assertTrue(path.path("get").isObject()); assertTrue(path.path("put").isObject());
            assertTrue(path.path("put").path("responses").has("409"));
        }
    }
}
