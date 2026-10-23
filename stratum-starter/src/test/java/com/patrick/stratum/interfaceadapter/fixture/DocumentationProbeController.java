package com.patrick.stratum.interfaceadapter.fixture;

import java.net.URI;

import com.patrick.stratum.common.api.ApiResponse;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 仅由文档测试显式装配的接口探针，用于比较生成模型与真实HTTP响应。 */
@TestComponent
@RestController
public class DocumentationProbeController {

    /** @return 自动包装的对象模型 */
    @GetMapping(path = "/documentation-probes/object", version = "1")
    ProbeResult object() { return new ProbeResult("文档验收", 2); }

    /** @return 已有标准外壳 */
    @GetMapping("/documentation-probes/wrapped")
    ApiResponse<ProbeResult> wrapped() { return ApiResponse.success(new ProbeResult("已包装", 3)); }

    /** @return 自动包装的字符串 */
    @GetMapping("/documentation-probes/string")
    String string() { return "文档验收字符串"; }

    /** 返回空成功结果。 */
    @GetMapping("/documentation-probes/empty")
    void empty() {}

    /** @return 带旧长度的对象响应，用于真实Servlet长度清理验收 */
    @GetMapping("/documentation-probes/created")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<ProbeResult> created() {
        return ResponseEntity.created(URI.create("/documentation-probes/object"))
                .contentLength(1).body(new ProbeResult("新建", 4));
    }

    /** @return 不应包装的二进制下载 */
    @GetMapping(path = "/documentation-probes/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    byte[] download() { return new byte[]{1, 2, 3}; }

    /**
     * 文档结果模型。
     * @param name 展示名称
     * @param quantity 数量
     */
    public record ProbeResult(String name, int quantity) {}
}
