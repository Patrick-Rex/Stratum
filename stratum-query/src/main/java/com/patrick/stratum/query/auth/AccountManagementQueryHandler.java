package com.patrick.stratum.query.auth;

import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 管理员账号分页处理器，读取前核对当前资格，参数绑定且不选择任何凭据列。 */
@Component
@RequiredArgsConstructor
public class AccountManagementQueryHandler implements QueryHandler<AccountManagementQuery, AccountManagementQuery.Row> {
    private final EasyEntityQuery database;
    private final AdministrationQueryAccess access;

    /** {@inheritDoc} */
    @Override public String queryCode() { return "auth.account.list"; }

    /** {@inheritDoc} */
    @Override public Class<AccountManagementQuery> queryType() { return AccountManagementQuery.class; }

    /** {@inheritDoc} */
    @Override public QueryResult<AccountManagementQuery.Row> handle(AccountManagementQuery query, QueryContext context) {
        access.require(context);
        try {
            var selection = database.queryable(AccountManagementView.class).configure(options -> options.setPrintSQL(false));
            for (var filter : query.spec().getFilters()) {
                selection.where(row -> {
                    switch (filter.field()) {
                        case ID -> row.id().eq(filter.value().toString());
                        case ENABLED -> row.enabled().eq((Boolean) filter.value());
                        case VERSION -> {
                            if (filter.operator() == QuerySpec.Operator.BETWEEN) {
                                row.version().ge((Long) filter.value()); row.version().le((Long) filter.upper());
                            } else row.version().eq((Long) filter.value());
                        }
                        case USERNAME -> {
                            if (filter.operator() == QuerySpec.Operator.EQ) row.username().eq((String) filter.value());
                            else row.expression().sql("{0} LIKE {1} ESCAPE '!'", parameters -> {
                                parameters.expression(row.username()); parameters.value(literalPattern((String) filter.value()));
                            });
                        }
                    }
                });
            }
            for (var sort : query.spec().getSorts()) {
                boolean ascending = sort.direction() == SortDirection.ASC;
                selection.orderBy(row -> {
                    switch (sort.field()) {
                        case ID -> row.id().orderBy(ascending);
                        case USERNAME -> row.username().orderBy(ascending);
                        case ENABLED -> row.enabled().orderBy(ascending);
                        case VERSION -> row.version().orderBy(ascending);
                    }
                });
            }
            if (query.spec().getSorts().stream().noneMatch(sort -> sort.field() == AccountManagementQuery.Field.ID)) selection.orderBy(row -> row.id().asc());
            var page = query.spec().getPageRequest(); var result = selection.toPageResult(page.getPage(), page.getPageSize());
            var rows = result.getData().stream().map(row -> new AccountManagementQuery.Row(UUID.fromString(row.getId()),
                    row.getUsername(), row.getEnabled(), row.getVersion())).toList();
            return new QueryResult<>(page.getPage(), page.getPageSize(), result.getTotal(), rows);
        } catch (EasyQueryException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }

    /**
     * 将用户文本按字面包含匹配，通配符和转义符保持参数值。
     * @param value 已有界文本
     * @return 已转义LIKE参数
     */
    private static String literalPattern(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
