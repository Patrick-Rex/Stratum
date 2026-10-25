package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.patrick.stratum.application.masterdata.MasterDataApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 实际HTTP登录后验证ERP命令主体和幂等回执使用服务器身份，而非请求体/头自报值。 */
class AuthenticatedErpIdentityHttpIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @LocalServerPort private int port;
    private final JdbcTemplate jdbc;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork unitOfWork;
    private final VersionedCache cache;
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> ids = new ArrayList<>();
    private final String trace = UUID.randomUUID().toString();
    private final String key = UUID.randomUUID().toString();
    private String secondAccount;
    private final String secondRole = UUID.randomUUID().toString();

    /**
     * 注入独立断言、账号夹具与清理工具。
     * @param jdbc 数据库断言
     * @param accounts 第二账号夹具
     * @param passwords 密码散列
     * @param unitOfWork 写工作单元
     * @param cache 缓存失效
     * @param redis 缓存清理
     */
    @Autowired
    AuthenticatedErpIdentityHttpIntegrationTest(JdbcTemplate jdbc, LocalAccountRepository accounts, PasswordHasher passwords,
            UnitOfWork unitOfWork, VersionedCache cache, StringRedisTemplate redis) {
        this.jdbc = jdbc; this.accounts = accounts; this.passwords = passwords;
        this.unitOfWork = unitOfWork; this.cache = cache; this.redis = redis;
    }

    /** 只清理自己的业务记录、随机账号、事件与缓存。 */
    @AfterEach
    void cleanBusiness() {
        jdbc.update("DELETE FROM outbox_event WHERE trace_id = ?", trace);
        jdbc.update("DELETE FROM idempotency_receipt WHERE request_key = ?", key);
        for (String id : ids) {
            jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            String cacheKey = MasterDataApplicationService.cacheKey(id);
            cache.invalidate(cacheKey, 100, MasterDataApplicationService.CACHE_POLICY);
            redis.delete(List.of(cacheKey, "null:" + cacheKey.substring(4), "version:" + cacheKey.substring(4)));
        }
        if (secondAccount != null) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id = ?", secondAccount);
            jdbc.update("DELETE FROM auth_user WHERE id = ?", secondAccount);
        }
        jdbc.update("DELETE FROM auth_role_permission WHERE role_id = ?", secondRole);
        jdbc.update("DELETE FROM auth_role WHERE id = ?", secondRole);
    }

    /**
     * 两个账号使用相同幂等键分别创建一次，伪造用户字段不能访问另一账号回执。
     * @throws Exception HTTP失败
     */
    @Test
    void isolatesIdempotencyByAuthenticatedAccount() throws Exception {
        String secondUsername = "second-" + UUID.randomUUID(); String secret = UUID.randomUUID().toString();
        char[] raw = secret.toCharArray();
        try {
            var account = LocalAccount.create(secondUsername, passwords.hash(raw), false); secondAccount = account.getId();
            unitOfWork.execute(() -> {
                accounts.insert(account);
                jdbc.update("INSERT INTO auth_role(id,code,name,version) VALUES (?,?,?,0)", secondRole, "identity-" + secondRole, "客户维护测试");
                jdbc.update("INSERT INTO auth_role_permission(role_id,permission_id) SELECT ?,id FROM auth_permission WHERE code=? AND type='API'", secondRole, "erp.customer.write");
                jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES (?,?)", secondAccount, secondRole);
                return null;
            });
        } finally { Arrays.fill(raw, '\0'); }
        var other = login(secondUsername, secret);
        var firstPayload = Map.of("code", UUID.randomUUID().toString(), "name", "主体甲", "subjectId", other.subjectId());
        var secondPayload = Map.of("code", UUID.randomUUID().toString(), "name", "主体乙", "subjectId", subjectId());
        var first = create(accessToken(), other.subjectId(), firstPayload);
        assertEquals(first, create(accessToken(), other.subjectId(), firstPayload));
        var second = create(other.accessToken(), subjectId(), secondPayload);
        assertNotEquals(first.path("id").asText(), second.path("id").asText());
        var scopes = jdbc.queryForList("SELECT scope FROM idempotency_receipt WHERE request_key = ?", String.class, key);
        assertEquals(java.util.Set.of("master:create:v1:CUSTOMER:" + subjectId(), "master:create:v1:CUSTOMER:" + other.subjectId()), java.util.Set.copyOf(scopes));
        assertEquals(2, scopes.size());
        assertFalse(scopes.stream().anyMatch(scope -> scope.contains("local-erp")));
    }

    /**
     * 创建主数据并记录资源ID；写入请求始终走真实认证HTTP。
     * @param access 已登录Access
     * @param forgedUser 自报用户头，服务器必须忽略
     * @param payload 请求体
     * @return 主数据结果
     * @throws Exception HTTP失败
     */
    private JsonNode create(String access, String forgedUser, Object payload) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/erp/masters/CUSTOMER"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("X-Trace-Id", trace)
                    .header("Authorization", "Bearer " + access).header("X-User-Id", forgedUser).header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var data = json.readTree(response.body()).path("data");
            if (data.has("id") && !ids.contains(data.path("id").asText())) { ids.add(data.path("id").asText()); }
            assertEquals(201, response.statusCode());
            return data;
        }
    }
}
