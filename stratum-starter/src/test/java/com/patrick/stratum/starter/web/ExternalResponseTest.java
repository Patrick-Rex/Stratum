package com.patrick.stratum.starter.web;

import java.util.Map;

import com.patrick.stratum.interfaceadapter.response.ApiResponseAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 验证业务包以外的控制器保持原始协议，避免破坏OpenAPI与管理端点。 */
class ExternalResponseTest {

    /**
     * 非业务控制器不套用统一响应。
     * @throws Exception MVC请求失败
     */
    @Test
    void leavesExternalControllerUntouched() throws Exception {
        var json = JsonMapper.builder().build();
        var mvc = MockMvcBuilders.standaloneSetup(new ExternalController())
                .setControllerAdvice(new ApiResponseAdvice(json)).build();
        var result = mvc.perform(get("/external-probe")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        var body = json.readTree(result.getResponse().getContentAsByteArray());
        assertEquals("original", body.path("protocol").asString());
        assertFalse(body.has("code"));
        assertFalse(body.has("data"));
    }

    /** 模拟第三方独立协议控制器。 */
    @RestController
    static class ExternalController {
        /** @return 独立协议内容 */
        @GetMapping("/external-probe")
        Map<String, String> response() { return Map.of("protocol", "original"); }
    }
}
