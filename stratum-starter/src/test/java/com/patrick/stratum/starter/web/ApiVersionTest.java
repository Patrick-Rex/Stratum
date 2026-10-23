package com.patrick.stratum.starter.web;

import com.patrick.stratum.common.api.ApiResponse;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.interfaceadapter.web.ApiVersionConfiguration;
import com.patrick.stratum.interfaceadapter.web.RequestTraceFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 使用实际 MVC 路由与异常链验证版本头协议，不连接数据库或缓存。 */
class ApiVersionTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();

    /** 创建 MVC 上下文并挂载实际请求关联过滤器。 */
    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new RequestTraceFilter()).build();
    }

    /** 关闭上下文并核对请求线程未泄漏 traceId。 */
    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /** 缺失版本头时路由到版本1接口。
     * @throws Exception MVC 请求失败
     */
    @Test
    void defaultsToVersionOne() throws Exception {
        var result = mvc.perform(get("/version-probe").header("X-Trace-Id", "version-default")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertEquals("version-default", body.path("traceId").asString());
        assertEquals("v1", body.path("data").asString());
    }

    /** 显式版本1及其语义版本等价形式均命中同一处理器。
     * @throws Exception MVC 请求失败
     */
    @Test
    void acceptsSupportedSemanticVersion() throws Exception {
        for (String version : new String[]{"1", "1.0", "1.0.0"}) {
            var result = mvc.perform(get("/version-probe").header("X-API-Version", version)).andReturn();
            assertEquals(200, result.getResponse().getStatus());
            assertEquals("v1", json.readTree(result.getResponse().getContentAsString()).path("data").asString());
        }
    }

    /** 未支持版本或非法格式返回专用400码、空明细和相同traceId。
     * @throws Exception MVC 请求失败
     */
    @Test
    void rejectsUnsupportedAndMalformedVersions() throws Exception {
        for (String version : new String[]{"2", "1.1", "garbage", "", "1.0.0.1"}) {
            var result = mvc.perform(get("/version-probe").header("X-API-Version", version)
                    .header("X-Trace-Id", "version-error")).andReturn();
            assertVersionError(result);
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            assertEquals("version-error", body.path("traceId").asString());
            assertEquals("不支持的API版本", body.path("message").asString());
            assertEquals(0, body.path("details").size());
        }
    }

    /** 版本校验也覆盖未显式声明版本的业务处理器。
     * @throws Exception MVC 请求失败
     */
    @Test
    void rejectsUnsupportedVersionOnUnversionedHandler() throws Exception {
        assertVersionError(mvc.perform(get("/unversioned-probe").header("X-API-Version", "2")).andReturn());
    }

    /** 版本错误遵循已有英文消息选择逻辑。
     * @throws Exception MVC 请求失败
     */
    @Test
    void localizesVersionFailure() throws Exception {
        var result = mvc.perform(get("/version-probe").header("X-API-Version", "2")
                .header("Accept-Language", "en-US")).andReturn();
        assertVersionError(result);
        assertEquals("Unsupported API version", json.readTree(result.getResponse().getContentAsString())
                .path("message").asString());
    }

    /**
     * 核对专用错误码与HTTP状态。
     * @param result MVC执行结果
     * @throws java.io.UnsupportedEncodingException 响应字符编码不可用
     */
    private void assertVersionError(MvcResult result) throws java.io.UnsupportedEncodingException {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(40001, json.readTree(result.getResponse().getContentAsString()).path("code").asInt());
    }

    /** 仅装配测试所需MVC、消息与异常处理组件。 */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({ApiVersionConfiguration.class, GlobalExceptionHandler.class, ProbeController.class})
    static class TestConfiguration {
        /**
         * 加载生产消息资源并禁用系统区域回退。
         * @return 消息解析器
         */
        @Bean
        ResourceBundleMessageSource messageSource() {
            var source = new ResourceBundleMessageSource();
            source.setBasename("messages");
            source.setDefaultEncoding("UTF-8");
            source.setFallbackToSystemLocale(false);
            return source;
        }
    }

    /** 仅测试类路径可见的版本路由探针。 */
    @RestController
    static class ProbeController {
        /**
         * 返回版本1标记。
         * @return 标准响应
         */
        @GetMapping(path = "/version-probe", version = "1")
        ApiResponse<String> versioned() {
            return ApiResponse.success("v1");
        }

        /**
         * 返回无显式版本声明的探针响应。
         * @return 标准响应
         */
        @GetMapping("/unversioned-probe")
        ApiResponse<String> unversioned() {
            return ApiResponse.success("plain");
        }
    }
}
