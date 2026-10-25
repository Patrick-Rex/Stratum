package com.patrick.stratum.interfaceadapter.controller;

import java.net.URI;
import java.util.UUID;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.CurrentAuthorizationApplicationService;
import com.patrick.stratum.domain.masterdata.MasterDataPermissions;
import com.patrick.stratum.application.command.CommandGateway;
import com.patrick.stratum.application.masterdata.CreateMasterDataCommand;
import com.patrick.stratum.application.masterdata.DeleteMasterDataCommand;
import com.patrick.stratum.application.masterdata.RenameMasterDataCommand;
import com.patrick.stratum.application.masterdata.MasterDataResult;
import com.patrick.stratum.domain.masterdata.MasterData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 主数据HTTP写适配，业务规则委托命令入口；主体由已验证Access上下文提供。 */
@RestController
@RequestMapping(path = "/api/erp/masters/{kind}", version = "1")
@RequiredArgsConstructor
public class MasterDataController {
    private static final String IDEMPOTENCY_PATTERN = "[A-Za-z0-9._:-]{1,128}";
    private final CommandGateway commands;
    private final CurrentIdentity identities;
    private final CurrentAuthorizationApplicationService authorization;

    /**
     * 创建输入，不开放类别和主体字段。
     * @param code 固定编码
     * @param name 名称
     * @param baseUnit 物料基本单位，其他类别省略
     */
    @Schema(name = "CreateMasterDataRequest")
    public record CreateRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[^\\p{Cc}]*") @Schema(example = "M001") String code,
            @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cc}]*") @Schema(example = "钢板") String name,
            @Size(max = 32) @Pattern(regexp = "[^\\p{Cc}]*") @Schema(example = "千克") String baseUnit) { }

    /**
     * 改名输入，未包含固定编码和单位。
     * @param name 新名称
     * @param version 观察版本，必须显式提供
     */
    @Schema(name = "RenameMasterDataRequest")
    public record RenameRequest(
            @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cc}]*") @Schema(example = "冷轧钢板") String name,
            @NotNull @PositiveOrZero @Schema(example = "0") Long version) { }

    /**
     * 通过命令入口创建或重放主数据，返回固定资源位置。
     * @param kind 路由类别
     * @param key 幂等键
     * @param request 已校验输入
     * @return 201及结果快照
     * @apiNote 示例：POST /api/erp/masters/MATERIAL，携带Idempotency-Key与创建JSON。
     */
    @PostMapping
    @Operation(summary = "创建主数据", description = "需对应类别的erp.{material/customer/supplier/warehouse}.write权限；物料须提供baseUnit，其他类别不提供；主体来自服务器验证的Access身份。")
    @ApiResponse(responseCode = "201", description = "创建或重放成功，Location指向资源")
    public ResponseEntity<MasterDataResult> create(@PathVariable("kind") MasterData.Kind kind,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String key,
            @Valid @RequestBody CreateRequest request) {
        authorization.requireApi(MasterDataPermissions.write(kind));
        var result = commands.execute(CreateMasterDataCommand.builder().subjectId(identities.require().getName()).idempotencyKey(key)
                .kind(kind).code(request.code()).name(request.name()).baseUnit(request.baseUnit()).build()).data();
        return ResponseEntity.created(URI.create("/api/erp/masters/" + kind + "/" + result.getId())).body(result);
    }

    /**
     * 按版本改名，重复请求返回原快照。
     * @param kind 路由类别
     * @param id 资源UUID
     * @param key 幂等键
     * @param request 新名称和观察版本
     * @return 更新后的快照
     * @apiNote 示例：PATCH /api/erp/masters/MATERIAL/{id}，请求包含name和version。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "修改主数据名称", description = "需对应类别的erp.{material/customer/supplier/warehouse}.write权限；编码、类别及基本单位固定；版本冲突返回409。")
    public MasterDataResult rename(@PathVariable("kind") MasterData.Kind kind, @PathVariable("id") UUID id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String key,
            @Valid @RequestBody RenameRequest request) {
        authorization.requireApi(MasterDataPermissions.write(kind));
        return commands.execute(RenameMasterDataCommand.builder().subjectId(identities.require().getName()).idempotencyKey(key)
                .kind(kind).id(id.toString()).version(request.version()).name(request.name()).build()).data();
    }

    /**
     * 删除未引用资源，重放删除不再读取已删除对象。
     * @param kind 路由类别
     * @param id 资源UUID
     * @param key 幂等键
     * @param version 观察版本
     * @return 204空响应
     * @apiNote 示例：DELETE /api/erp/masters/MATERIAL/{id}?version=1。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除未引用主数据", description = "需对应类别的erp.{material/customer/supplier/warehouse}.write权限；须传观察版本，存在业务引用或版本过期返回409。")
    @ApiResponse(responseCode = "204", description = "删除或重放成功，无响应体")
    public ResponseEntity<Void> delete(@PathVariable("kind") MasterData.Kind kind, @PathVariable("id") UUID id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String key,
            @RequestParam("version") @PositiveOrZero long version) {
        authorization.requireApi(MasterDataPermissions.write(kind));
        commands.execute(DeleteMasterDataCommand.builder().subjectId(identities.require().getName()).idempotencyKey(key)
                .kind(kind).id(id.toString()).version(version).build());
        return ResponseEntity.noContent().build();
    }
}
