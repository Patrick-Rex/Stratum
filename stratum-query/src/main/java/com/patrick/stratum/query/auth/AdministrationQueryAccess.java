package com.patrick.stratum.query.auth;

import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 管理读模型共用的资格检查，权限规则委托Domain，权威状态只经QueryGateway读取。 */
@Component
@RequiredArgsConstructor
public class AdministrationQueryAccess {
    // 处理器注册到网关后才需要嵌套查询，延迟解析避免构造期循环依赖，不缓存授权结果。
    private final ObjectProvider<QueryGateway> gateway;

    /**
     * 按服务器操作者读取当前资格并执行管理员领域规则。
     * @param context 服务器构造的查询上下文，不能从请求体绑定
     * @throws StratumException 匿名、非当前启用管理员或存储读取失败
     * @apiNote 示例：管理QueryHandler读取目标数据前调用{@code access.require(context)}。
     */
    public void require(QueryContext context) {
        String actor = context.operatorId().orElseThrow(() -> new StratumException(CommonErrorCode.UNAUTHORIZED));
        UUID userId;
        try { userId = UUID.fromString(actor); }
        catch (IllegalArgumentException failure) { throw new StratumException(CommonErrorCode.FORBIDDEN); }
        var result = gateway.getObject().execute(AdministratorQuery.create(userId), context);
        AdministrationPolicy.requireAdministrator(result.getItems().size() == 1 && Boolean.TRUE.equals(result.getItems().getFirst()));
    }
}
