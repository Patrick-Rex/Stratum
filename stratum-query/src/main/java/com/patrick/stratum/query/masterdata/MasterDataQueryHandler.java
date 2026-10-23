package com.patrick.stratum.query.masterdata;

import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.proxy.columns.types.SQLStringTypeColumn;
import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.masterdata.proxy.MasterDataViewProxy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 通过Easy-Query执行主数据只读查询，绑定参数并按ID补充稳定排序。 */
@Component
@RequiredArgsConstructor
public class MasterDataQueryHandler implements QueryHandler<MasterDataQuery, MasterDataQuery.Row> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override
    public String queryCode() { return "erp.master.list"; }

    /** {@inheritDoc} */
    @Override
    public Class<MasterDataQuery> queryType() { return MasterDataQuery.class; }

    /** {@inheritDoc} */
    @Override
    public QueryResult<MasterDataQuery.Row> handle(MasterDataQuery query, QueryContext context) {
        var selection = database.queryable(MasterDataView.class).where(row -> row.kind().eq(query.getKind().name()));
        for (var filter : query.spec().getFilters()) {
            selection.where(row -> {
                var column = column(row, filter.field());
                switch (filter.operator()) {
                    case EQ -> column.eq(filter.value().toString());
                    case CONTAINS -> row.expression().sql("{0} LIKE {1} ESCAPE '!'", parameters -> {
                        parameters.expression(column);
                        parameters.value(literalPattern((String) filter.value()));
                    });
                    case BETWEEN -> throw new IllegalArgumentException("主数据不支持区间条件");
                }
            });
        }
        for (var sort : query.spec().getSorts()) {
            selection.orderBy(row -> column(row, sort.field()).orderBy(sort.direction() == SortDirection.ASC));
        }
        if (query.spec().getSorts().stream().noneMatch(sort -> sort.field() == MasterDataQuery.Field.ID)) {
            selection.orderBy(row -> row.id().asc());
        }
        var page = query.spec().getPageRequest();
        var result = selection.toPageResult(page.getPage(), page.getPageSize());
        var rows = result.getData().stream().map(row -> new MasterDataQuery.Row(UUID.fromString(row.getId()),
                MasterData.Kind.valueOf(row.getKind()), row.getCode(), row.getName(), row.getBaseUnit(), row.getVersion())).toList();
        return new QueryResult<>(page.getPage(), page.getPageSize(), result.getTotal(), rows);
    }

    /**
     * 将枚举映射到生成的列引用，不接受动态属性路径。
     * @param row 当前查询代理
     * @param field 已验证字段
     * @return 类型化文本列
     */
    private SQLStringTypeColumn<MasterDataViewProxy> column(MasterDataViewProxy row, MasterDataQuery.Field field) {
        return switch (field) { case ID -> row.id(); case CODE -> row.code(); case NAME -> row.name(); };
    }

    /**
     * 使用固定感叹号转义，避免反斜线受数据库默认LIKE转义规则影响；结果仍以参数绑定。
     * @param text 字面包含文本
     * @return 有界LIKE参数
     */
    private String literalPattern(String text) {
        return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
