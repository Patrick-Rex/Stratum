package com.patrick.stratum.starter.integration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.interfaceadapter.controller.AccountManagementController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证账号创建、启停、角色替换及授权、版本和密码边界。 */
class AccountManagementHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private AuthSessionApplicationService authentication;
    @Autowired private AccountPermissions permissions;

    /**
     * 创建账号无默认授权，分配角色与启停均撤销旧会话，重新登录可继续使用。
     * @throws Exception HTTP失败
     */
    @Test
    void createsAssignsAndChangesStatusWithRealRevocation() throws Exception {
        String username = "account-http-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        UUID id = createAccount(username, password);
        assertTrue(permissions.findByUserId(id, "account-http").orElseThrow().permissions().isEmpty());
        assertEquals(409, request("POST", "/api/auth/accounts", accessToken(), Map.of("username", username, "password", password)).statusCode());
        var initial = login(username, password);
        String administrator = jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class);
        var assigned = request("PUT", "/api/auth/accounts/" + id + "/roles", accessToken(), Map.of("version", 0, "roleIds", List.of(administrator)));
        assertEquals(1, data(assigned, 200).path("version").asLong());
        assertTrue(assigned.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals(PermissionCatalog.permissions(), permissions.findByUserId(id, "account-http").orElseThrow().permissions());
        unauthorized(() -> authentication.authenticateAccess(initial.accessToken()));
        unauthorized(() -> authentication.refresh(initial.refreshToken()));
        var privileged = login(username, password);
        assertEquals(2, data(request("PATCH", "/api/auth/accounts/" + id + "/status", accessToken(), Map.of("version", 1, "enabled", false)), 200).path("version").asLong());
        unauthorized(() -> authentication.authenticateAccess(privileged.accessToken()));
        assertEquals(3, data(request("PATCH", "/api/auth/accounts/" + id + "/status", accessToken(), Map.of("version", 2, "enabled", true)), 200).path("version").asLong());
        unauthorized(() -> authentication.refresh(privileged.refreshToken()));
        var enabled = login(username, password);
        assertEquals(4, data(request("PUT", "/api/auth/accounts/" + id + "/roles", accessToken(), Map.of("version", 3, "roleIds", List.of())), 200).path("version").asLong());
        unauthorized(() -> authentication.authenticateAccess(enabled.accessToken()));
        assertTrue(permissions.findByUserId(id, "account-http").orElseThrow().permissions().isEmpty());
    }

    /**
     * 匿名、Refresh或普通账号不能管理，伪造操作者字段和身份头均不能提权。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsUntrustedManagementAndUnsupportedVersions() throws Exception {
        String username = "ordinary-http-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        UUID id = createAccount(username, password); var ordinary = login(username, password);
        var create = Map.of("username", "forged-" + UUID.randomUUID(), "password", password, "userId", subjectId());
        assertEquals(401, request("POST", "/api/auth/accounts", null, create).statusCode());
        assertEquals(401, request("POST", "/api/auth/accounts", refreshToken(), create).statusCode());
        assertEquals(403, request("POST", "/api/auth/accounts", ordinary.accessToken(), create).statusCode());
        assertEquals(403, request("PATCH", "/api/auth/accounts/" + id + "/status", ordinary.accessToken(), Map.of("version", 0, "enabled", false, "userId", subjectId())).statusCode());
        assertEquals(403, request("PUT", "/api/auth/accounts/" + id + "/roles", ordinary.accessToken(), Map.of("version", 0, "roleIds", List.of(), "userId", subjectId())).statusCode());
        assertEquals(400, request("PATCH", "/api/auth/accounts/" + id + "/status", accessToken(), Map.of("version", 0, "enabled", false), "2").statusCode());
        assertTrue(jdbc.queryForObject("SELECT enabled FROM auth_user WHERE id=?", Boolean.class, id.toString()));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM auth_user WHERE id=?", Long.class, id.toString()));
    }

    /**
     * HTTP保留最后管理员、目标缺失、角色缺失和旧版本的明确失败语义。
     * @throws Exception HTTP失败
     */
    @Test
    void protectsTheLastAdministratorAndRejectsStaleChanges() throws Exception {
        assertEquals(409, request("PATCH", "/api/auth/accounts/" + subjectId() + "/status", accessToken(), Map.of("version", 0, "enabled", false)).statusCode());
        assertEquals(409, request("PUT", "/api/auth/accounts/" + subjectId() + "/roles", accessToken(), Map.of("version", 0, "roleIds", List.of())).statusCode());
        UUID id = createAccount("versions-http-" + UUID.randomUUID(), UUID.randomUUID().toString());
        assertEquals(409, request("PATCH", "/api/auth/accounts/" + id + "/status", accessToken(), Map.of("version", 1, "enabled", false)).statusCode());
        assertEquals(409, request("PUT", "/api/auth/accounts/" + id + "/roles", accessToken(), Map.of("version", 0, "roleIds", List.of(UUID.randomUUID()))).statusCode());
        assertEquals(404, request("PATCH", "/api/auth/accounts/" + UUID.randomUUID() + "/status", accessToken(), Map.of("version", 0, "enabled", false)).statusCode());
        assertEquals(404, request("PUT", "/api/auth/accounts/" + UUID.randomUUID() + "/roles", accessToken(), Map.of("version", 0, "roleIds", List.of())).statusCode());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM auth_user WHERE id=?", Long.class, id.toString()));
    }

    /**
     * 输入类型、非空和数量限制映射400，UTF-8超72字节及空白密码拒绝且不回显。
     * @throws Exception HTTP失败
     */
    @Test
    void boundsInputsWithoutExposingPasswords() throws Exception {
        String username = "invalid-http-" + UUID.randomUUID();
        for (String password : List.of("密".repeat(25), "x".repeat(73), "   ")) {
            var response = request("POST", "/api/auth/accounts", accessToken(), Map.of("username", username, "password", password));
            assertEquals(400, response.statusCode()); assertFalse(response.body().contains(password));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_user WHERE username=?", Integer.class, username));
        assertEquals(400, request("POST", "/api/auth/accounts", accessToken(), Map.of("username", "bad name", "password", "temporary")).statusCode());
        assertEquals(400, request("POST", "/api/auth/accounts", accessToken(), Map.of("username", username)).statusCode());
        assertEquals(400, request("PATCH", "/api/auth/accounts/" + subjectId() + "/status", accessToken(), Map.of("enabled", true)).statusCode());
        assertEquals(400, request("PATCH", "/api/auth/accounts/" + subjectId() + "/status", accessToken(), Map.of("version", -1, "enabled", true)).statusCode());
        var tooMany = IntStream.range(0, 65).mapToObj(index -> UUID.randomUUID()).toList();
        assertEquals(400, request("PUT", "/api/auth/accounts/" + subjectId() + "/roles", accessToken(), Map.of("version", 0, "roleIds", tooMany)).statusCode());
        assertEquals(400, request("PUT", "/api/auth/accounts/" + subjectId() + "/roles", accessToken(), Map.of("version", 0, "roleIds", Arrays.asList((UUID) null))).statusCode());
        var input = new AccountManagementController.CreateRequest(username, "secret-marker".toCharArray());
        assertFalse(input.toString().contains("secret-marker")); assertFalse(json.writeValueAsString(input).contains("secret-marker"));
        Arrays.fill(input.password(), '\0');
    }

    /**
     * 断言撤销令牌不可再次使用，不记录令牌原文。
     * @param action 认证动作
     */
    private static void unauthorized(Runnable action) {
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, action::run).getErrorCode());
    }
}
