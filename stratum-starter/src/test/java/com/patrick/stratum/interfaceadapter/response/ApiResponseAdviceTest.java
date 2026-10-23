package com.patrick.stratum.interfaceadapter.response;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.patrick.stratum.common.api.ApiResponse;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.interfaceadapter.web.RequestTraceFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 验证实际消息转换链的响应包装，不改变下载及无响应体的HTTP语义。 */
class ApiResponseAdviceTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    /** 装配业务控制器、正式Advice及关联过滤器。 */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiResponseAdvice(json)).addFilters(new RequestTraceFilter()).build();
    }

    /** 请求结束后不应留下关联上下文。 */
    @AfterEach
    void verifyCleanup() {
        assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /** 对象响应产生统一外壳和一致traceId。
     * @throws Exception MVC请求失败
     */
    @Test
    void wrapsObjectResponse() throws Exception {
        var result = request("/response/object");
        JsonNode body = assertSuccess(result);
        assertEquals("物料", body.path("data").path("name").asString());
    }

    /** 字符串经JSON序列化，保留引号与中文而不发生类型转换错误。
     * @throws Exception MVC请求失败
     */
    @Test
    void wrapsStringResponseAsJson() throws Exception {
        var result = request("/response/string");
        assertEquals(MediaType.APPLICATION_JSON_VALUE, result.getResponse().getContentType());
        assertEquals("字符串\"", assertSuccess(result).path("data").asString());
    }

    /** 空对象和void成功响应都保留统一结构。
     * @throws Exception MVC请求失败
     */
    @Test
    void wrapsNullAndVoidResponses() throws Exception {
        for (String path : new String[]{"/response/null", "/response/void"}) {
            assertTrue(assertSuccess(request(path)).path("data").isNull());
        }
    }

    /** 已有成功或失败外壳保持单层结构和原有错误码。
     * @throws Exception MVC请求失败
     */
    @Test
    void preservesExistingEnvelopes() throws Exception {
        assertEquals("wrapped", assertSuccess(request("/response/wrapped")).path("data").asString());
        var result = request("/response/failure");
        assertEquals(409, result.getResponse().getStatus());
        var body = json.readTree(result.getResponse().getContentAsByteArray());
        assertEquals(40900, body.path("code").asInt());
        assertEquals("response-test", body.path("traceId").asString());
    }

    /** 包装创建结果时保留状态与Location并移除失效的原载荷长度。
     * @throws Exception MVC请求失败
     */
    @Test
    void preservesCreatedStatusAndHeaders() throws Exception {
        var result = request("/response/created");
        assertEquals(201, result.getResponse().getStatus());
        assertEquals("/resources/one", result.getResponse().getHeader("Location"));
        assertEquals("created", assertSuccess(result).path("data").asString());
        String length = result.getResponse().getHeader("Content-Length");
        if (length != null) assertEquals(result.getResponse().getContentAsByteArray().length, Integer.parseInt(length));
    }

    /** 无内容、未修改和二进制下载不写入JSON外壳。
     * @throws Exception MVC请求失败
     */
    @Test
    void preservesNoBodyStatusesAndBinaryDownload() throws Exception {
        for (String path : new String[]{"/response/no-content", "/response/not-modified"}) {
            assertEquals(0, request(path).getResponse().getContentAsByteArray().length);
        }
        var result = request("/response/download");
        assertArrayEquals(new byte[]{1, 2, 3}, result.getResponse().getContentAsByteArray());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM_VALUE, result.getResponse().getContentType());
    }

    /**
     * 执行带稳定关联标识的请求。
     * @param path 路径
     * @return MVC响应
     * @throws Exception MVC请求失败
     */
    private MvcResult request(String path) throws Exception {
        return mvc.perform(get(path).header("X-Trace-Id", "response-test")).andReturn();
    }

    /**
     * 核对成功响应必填字段并返回JSON树。
     * @param result MVC响应
     * @return JSON根节点
     */
    private JsonNode assertSuccess(MvcResult result) {
        assertTrue(result.getResponse().getStatus() < 300);
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
        assertEquals(0, body.path("code").asInt());
        assertEquals("OK", body.path("message").asString());
        assertEquals("response-test", body.path("traceId").asString());
        assertEquals("response-test", result.getResponse().getHeader("X-Trace-Id"));
        return body;
    }

    /** 提供不同消息转换器及状态的测试端点。 */
    @RestController
    static class ProbeController {
        /** @return 对象载荷 */
        @GetMapping("/response/object")
        Map<String, String> object() { return Map.of("name", "物料"); }

        /** @return 字符串载荷 */
        @GetMapping("/response/string")
        String string() { return "字符串\""; }

        /** @return 空载荷 */
        @GetMapping("/response/null")
        Object nullBody() { return null; }

        /** 返回无数据的成功结果。 */
        @GetMapping("/response/void")
        void voidBody() {}

        /** @return 已包装成功结果 */
        @GetMapping("/response/wrapped")
        ApiResponse<String> wrapped() { return ApiResponse.success("wrapped"); }

        /** @return 已包装冲突结果 */
        @GetMapping("/response/failure")
        ResponseEntity<ApiResponse<Void>> failure() {
            return ResponseEntity.status(409).body(ApiResponse.failure(CommonErrorCode.CONFLICT, List.of()));
        }

        /** @return 带原始长度与Location的创建结果 */
        @GetMapping("/response/created")
        ResponseEntity<String> created() {
            return ResponseEntity.created(URI.create("/resources/one"))
                    .contentLength("created".getBytes(StandardCharsets.UTF_8).length).body("created");
        }

        /** @return 204无内容响应 */
        @GetMapping("/response/no-content")
        ResponseEntity<Void> noContent() { return ResponseEntity.noContent().build(); }

        /** @return 304未修改响应 */
        @GetMapping("/response/not-modified")
        ResponseEntity<Void> notModified() { return ResponseEntity.status(304).build(); }

        /** @return 原始二进制下载 */
        @GetMapping("/response/download")
        ResponseEntity<byte[]> download() {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(new byte[]{1, 2, 3});
        }
    }
}
