package com.patrick.stratum.interfaceadapter.response;

import java.util.List;
import java.util.Map;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.interfaceadapter.web.RequestTraceFilter;
import com.patrick.stratum.starter.web.GlobalExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 验证真实MVC校验、协议异常与统一错误映射，禁止回传解析原值和内部错误。 */
class ApiValidationTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    /** 装配生产Advice和实际Jakarta校验器。 */
    @BeforeEach
    void setUp() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController()).setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler(messages), new ApiResponseAdvice(json))
                .addFilters(new RequestTraceFilter()).build();
    }

    /** 关闭校验器并检查线程上下文清理。 */
    @AfterEach
    void tearDown() {
        if (validator != null) validator.close();
        assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /** 合法请求通过校验并被包装为标准成功结构。
     * @throws Exception MVC请求失败
     */
    @Test
    void acceptsValidBody() throws Exception {
        var result = perform(post("/validation/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"物料\",\"password\":\"long-test-secret\"}"));
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(0, body(result).path("code").asInt());
        assertFalse(result.getResponse().getContentAsString().contains("long-test-secret"));
    }

    /** 字段校验只返回字段定位，不暴露被拒绝的值。
     * @throws Exception MVC请求失败
     */
    @Test
    void reportsSafeFieldDetails() throws Exception {
        var result = perform(post("/validation/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"password\":\"secret123\"}"));
        assertError(result, 400, 40000);
        assertEquals(2, body(result).path("details").size());
        assertTrue(body(result).path("details").toString().contains("password"));
        assertFalse(result.getResponse().getContentAsString().contains("secret123"));
    }

    /** 非法JSON、字段类型和缺失请求体均映射400且不回传解析异常。
     * @throws Exception MVC请求失败
     */
    @Test
    void rejectsMalformedJsonAndMissingBody() throws Exception {
        for (String payload : List.of("{\"name\":secret123", "{\"name\":{\"secret123\":1}}", "")) {
            var result = perform(post("/validation/body").contentType(MediaType.APPLICATION_JSON).content(payload));
            assertError(result, 400, 40000);
            assertEquals(0, body(result).path("details").size());
            assertFalse(result.getResponse().getContentAsString().contains("secret123"));
        }
    }

    /** 缺失参数、类型转换失败及方法参数约束均拒绝为400。
     * @throws Exception MVC请求失败
     */
    @Test
    void validatesRequestParameters() throws Exception {
        assertError(perform(get("/validation/quantity")), 400, 40000);
        assertError(perform(get("/validation/quantity").param("quantity", "secret123")), 400, 40000);
        assertError(perform(get("/validation/quantity").param("quantity", "0")), 400, 40000);
        assertEquals(200, perform(get("/validation/quantity").param("quantity", "1")).getResponse().getStatus());
    }

    /** 协议异常保留405、415、404和Allow头，同时返回统一错误结构。
     * @throws Exception MVC请求失败
     */
    @Test
    void preservesProtocolStatusAndHeaders() throws Exception {
        var method = perform(delete("/validation/body"));
        assertError(method, 405, 40000);
        assertTrue(method.getResponse().getHeader("Allow").contains("POST"));
        assertError(perform(post("/validation/body").contentType(MediaType.TEXT_PLAIN).content("secret123")), 415, 40000);
        assertError(perform(get("/missing-resource")), 404, 40400);
    }

    /** 业务异常保留明确错误码和已由业务层筛选的安全明细。
     * @throws Exception MVC请求失败
     */
    @Test
    void mapsBusinessFailures() throws Exception {
        for (CommonErrorCode code : List.of(CommonErrorCode.UNAUTHORIZED, CommonErrorCode.FORBIDDEN,
                CommonErrorCode.NOT_FOUND, CommonErrorCode.CONFLICT)) {
            var result = perform(get("/validation/business/" + code.name()));
            assertError(result, code.getCode() / 100, code.getCode());
            assertEquals("safe-detail", body(result).path("details").get(0).asString());
        }
    }

    /** 内部异常和返回值校验失败均为500，不能误报成请求参数错误。
     * @throws Exception MVC请求失败
     */
    @Test
    void hidesInternalFailuresAndDistinguishesReturnValidation() throws Exception {
        for (String path : List.of("/validation/internal", "/validation/return")) {
            var result = perform(get(path));
            assertError(result, 500, 50000);
            assertEquals(0, body(result).path("details").size());
            assertFalse(result.getResponse().getContentAsString().contains("secret123"));
        }
    }

    /** 参数异常和HTTP异常的reason不直接回传客户端。
     * @throws Exception MVC请求失败
     */
    @Test
    void hidesUntrustedExceptionMessages() throws Exception {
        for (String path : List.of("/validation/argument", "/validation/protocol")) {
            var result = perform(get(path).header("Accept-Language", "en-US"));
            assertError(result, 400, 40000);
            assertEquals("Request parameters or protocol are invalid", body(result).path("message").asString());
            assertFalse(result.getResponse().getContentAsString().contains("secret123"));
        }
    }

    /**
     * 执行请求并传入固定traceId。
     * @param request 请求构建器
     * @return MVC结果
     * @throws Exception MVC请求失败
     */
    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Trace-Id", "validation-test")).andReturn();
    }

    /**
     * 解析响应JSON。
     * @param result MVC结果
     * @return JSON根节点
     */
    private JsonNode body(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * 核对错误状态和标准模型。
     * @param result MVC结果
     * @param status HTTP状态
     * @param code 业务错误码
     */
    private void assertError(MvcResult result, int status, int code) {
        assertEquals(status, result.getResponse().getStatus());
        assertEquals(code, body(result).path("code").asInt());
        assertTrue(body(result).path("details").isArray());
        assertEquals("validation-test", body(result).path("traceId").asString());
        assertEquals("validation-test", result.getResponse().getHeader("X-Trace-Id"));
    }

    /**
     * 参数校验探针，不持久化或回传敏感值。
     * @param name 非空名称
     * @param password 最小长度校验字段
     */
    record ProbeRequest(@NotBlank String name, @Size(min = 12) String password) {}

    /** 只存在测试类路径的校验及错误分支端点。 */
    @TestComponent
    @RestController
    static class ProbeController {
        /**
         * 返回校验成功标记。
         * @param request 已校验请求
         * @return 成功标记
         */
        @PostMapping(path = "/validation/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Boolean> body(@Valid @RequestBody ProbeRequest request) { return Map.of("accepted", true); }

        /**
         * 返回合法数量。
         * @param quantity 正数量
         * @return 数量
         */
        @GetMapping("/validation/quantity")
        int quantity(@RequestParam("quantity") @Min(1) int quantity) { return quantity; }

        /**
         * 抛出指定业务异常。
         * @param kind 错误码名称
         * @return 不会正常返回
         */
        @GetMapping("/validation/business/{kind}")
        Object business(@PathVariable("kind") String kind) {
            throw new StratumException(CommonErrorCode.valueOf(kind), "safe-detail");
        }

        /** @return 不会正常返回，模拟服务内部失败 */
        @GetMapping("/validation/internal")
        Object internal() { throw new IllegalStateException("internal-secret123"); }

        /** @return 违反服务返回值约束的结果 */
        @GetMapping("/validation/return")
        @Min(1)
        int invalidReturn() { return 0; }

        /** @return 不会正常返回，模拟含原始输入的参数异常 */
        @GetMapping("/validation/argument")
        Object argument() { throw new IllegalArgumentException("secret123"); }

        /** @return 不会正常返回，模拟含内部原因的协议异常 */
        @GetMapping("/validation/protocol")
        Object protocol() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret123"); }
    }
}
