package com.patrick.stratum.interfaceadapter.web;

import java.util.ArrayList;
import java.util.List;

import com.patrick.stratum.common.api.ApiResponse;
import com.patrick.stratum.common.error.CommonErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 对齐OpenAPI描述与统一响应Advice，使用生成文档作为Knife4j唯一数据来源。 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    /**
     * 提供本项目的文档元数据。
     * @return OpenAPI元数据
     * @apiNote 使用示例：Springdoc自动合并到 /v3/api-docs。
     */
    @Bean
    public OpenAPI stratumOpenApi() {
        return new OpenAPI().info(new Info().title("Stratum ERP API").version("1")
                .description("Stratum 通用进销存接口；版本通过 X-API-Version 传入，缺省为1。"));
    }

    /**
     * 为生成的操作补齐响应外壳、版本头和通用错误。
     * @return 业务接口文档定制器
     * @apiNote 使用示例：新增业务Controller自动获得统一契约描述。
     */
    @Bean
    public OperationCustomizer stratumOperationContract() {
        return this::customize;
    }

    /**
     * 将内联响应模型注册为命名组件，兼容Knife4j的嵌套模型展示。
     * @return 文档级响应模型定制器
     * @apiNote 使用示例：生成文档时自动把内联对象替换为本地Schema引用。
     */
    @Bean
    public OpenApiCustomizer stratumResponseSchemas() {
        return document -> {
            if (document.getPaths() == null) return;
            if (document.getComponents() == null) document.setComponents(new Components());
            document.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                    operation.getResponses().forEach((status, response) -> {
                        if (response.getContent() == null) return;
                        response.getContent().forEach((mediaType, media) -> {
                            Schema<?> schema = media.getSchema();
                            if (schema == null || schema.get$ref() != null || schema.getProperties() == null) return;
                            String name = "StratumEnvelope_" + operation.getOperationId() + "_" + status + "_"
                                    + mediaType.replaceAll("[^A-Za-z0-9]", "_");
                            document.getComponents().addSchemas(name, schema);
                            media.setSchema(new Schema<>().$ref("#/components/schemas/" + name));
                        });
                    })));
        };
    }

    /**
     * 根据实际Java返回类型和媒体类型定制业务操作。
     * @param operation 已生成操作
     * @param handler 对应处理方法
     * @return 补齐契约的操作
     */
    private Operation customize(Operation operation, HandlerMethod handler) {
        String packageName = handler.getBeanType().getPackageName();
        if (!packageName.startsWith("com.patrick.stratum.interfaceadapter.")
                && !packageName.startsWith("com.patrick.stratum.query.")) return operation;
        if (operation.getParameters() == null) operation.setParameters(new ArrayList<>());
        if (operation.getParameters().stream().noneMatch(parameter ->
                "header".equals(parameter.getIn()) && "X-API-Version".equalsIgnoreCase(parameter.getName()))) {
            operation.addParametersItem(new Parameter().in("header").name("X-API-Version").required(false)
                    .description("API版本，缺省为1；1.0与1.0.0等价")
                    .schema(new StringSchema()._default("1").example("1")));
        }
        ResolvableType resultType = ResolvableType.forMethodReturnType(handler.getMethod());
        if (HttpEntity.class.isAssignableFrom(resultType.resolve(Object.class))) {
            resultType = resultType.as(HttpEntity.class).getGeneric(0);
        }
        Class<?> bodyType = resultType.resolve(Object.class);
        boolean jsonResponse = bodyType != byte[].class
                && !Resource.class.isAssignableFrom(bodyType)
                && !ResponseBodyEmitter.class.isAssignableFrom(bodyType)
                && !StreamingResponseBody.class.isAssignableFrom(bodyType);
        boolean wrap = !ApiResponse.class.isAssignableFrom(bodyType);
        operation.getResponses().forEach((status, response) -> {
            response.addHeaderObject("X-Trace-Id", new Header().description("请求关联标识").schema(new StringSchema()));
            if (!jsonResponse || !status.matches("2[0-9]{2}") || status.equals("204")) return;
            Content content = response.getContent();
            if (content == null || content.isEmpty()) {
                response.setContent(new Content().addMediaType("application/json",
                        new io.swagger.v3.oas.models.media.MediaType().schema(envelope(null, null))));
                return;
            }
            for (String mediaType : List.copyOf(content.keySet())) {
                if (mediaType.equals("application/json") || mediaType.endsWith("+json")
                        || (bodyType == String.class && mediaType.equals("text/plain"))
                        || mediaType.equals("*/*")) {
                    var media = content.remove(mediaType);
                    if (wrap) media.setSchema(envelope(media.getSchema(), null));
                    content.addMediaType(mediaType.equals("text/plain") || mediaType.equals("*/*")
                            ? "application/json" : mediaType, media);
                }
            }
        });
        addErrorResponse(operation, "400", "请求参数、协议或版本错误（40000/40001）", CommonErrorCode.BAD_REQUEST);
        addErrorResponse(operation, "404", "资源不存在或类别不匹配（40400）", CommonErrorCode.NOT_FOUND);
        addErrorResponse(operation, "409", "版本、库存、引用或幂等冲突（40900）", CommonErrorCode.CONFLICT);
        addErrorResponse(operation, "500", "服务内部错误（50000），不暴露内部异常", CommonErrorCode.INTERNAL_ERROR);
        return operation;
    }

    /**
     * 构建与ApiResponse字段一致的文档外壳，保留原数据Schema引用。
     * @param data 原始数据模型，空值表示无数据
     * @param error 错误码，空值表示成功模型
     * @return 响应对象模型
     */
    private Schema<?> envelope(Schema<?> data, CommonErrorCode error) {
        boolean success = error == null;
        var schema = new ObjectSchema();
        schema.addProperty("code", new IntegerSchema().example(success ? 0 : error.getCode()));
        schema.addProperty("message", new StringSchema().example(success ? "OK" : error.getDefaultMessage()));
        schema.addProperty("data", data == null ? new Schema<>().nullable(true) : data);
        schema.addProperty("details", new ArraySchema().items(new StringSchema()));
        schema.addProperty("traceId", new StringSchema().example("request-trace-id"));
        schema.setRequired(success ? List.of("code", "message", "data", "traceId")
                : List.of("code", "message", "details", "traceId"));
        return schema;
    }

    /**
     * 补齐未显式声明的通用错误，不覆盖业务注解的更具体描述。
     * @param operation 当前操作
     * @param status HTTP状态
     * @param description 对客户端安全的描述
     * @param error 对应错误码及默认消息
     */
    private void addErrorResponse(Operation operation, String status, String description, CommonErrorCode error) {
        operation.getResponses().putIfAbsent(status, new io.swagger.v3.oas.models.responses.ApiResponse()
                .description(description).addHeaderObject("X-Trace-Id", new Header().schema(new StringSchema()))
                .content(new Content().addMediaType("application/json",
                        new io.swagger.v3.oas.models.media.MediaType().schema(envelope(null, error)))));
    }
}
