package com.patrick.stratum.domain.masterdata;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.helper.ValidationHelper;
import com.patrick.stratum.domain.model.AggregateRoot;

/** 聚合根：物料、客户、供应商及仓库的主数据生命周期，固定类别、编码与基本单位。 */
public final class MasterData extends AggregateRoot<String> {
    /** 主数据业务类别；类别确定后不能切换。 */
    public enum Kind { MATERIAL, CUSTOMER, SUPPLIER, WAREHOUSE }

    private final Kind kind;
    private final String code;
    private final String baseUnit;
    private String name;

    /**
     * 统一创建与重建时的不变量校验。
     * @param id 规范UUID
     * @param kind 固定类别
     * @param code 固定编码
     * @param name 显示名称
     * @param baseUnit 物料基本单位，其他类别为空
     * @param version 已持久化版本，新增为空
     */
    private MasterData(String id, Kind kind, String code, String name, String baseUnit, Long version) {
        super(id);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = text(code, 64, "code");
        this.name = text(name, 200, "name");
        if (kind == Kind.MATERIAL) {
            this.baseUnit = text(baseUnit, 32, "baseUnit");
        } else {
            if (baseUnit != null) throw new IllegalArgumentException("只有物料可以设置基本单位");
            this.baseUnit = null;
        }
        if (version != null && version < 0) throw new IllegalArgumentException("版本不可为负数");
        setVersion(version);
    }

    /**
     * 在领域层生成UUID并创建尚未持久化的主数据。
     * @param kind 主数据类别
     * @param code 同类唯一编码，唯一性由仓储约束
     * @param name 名称
     * @param baseUnit 物料基本单位，其他类别必须为空
     * @return 新聚合
     * @throws IllegalArgumentException 文本为空、超长或基本单位与类别不符
     * @apiNote 示例：MasterData.create(Kind.MATERIAL, "M001", "钢板", "千克")。
     */
    public static MasterData create(Kind kind, String code, String name, String baseUnit) {
        return new MasterData(UUID.randomUUID().toString(), kind, code, name, baseUnit, null);
    }

    /**
     * 从仓储快照重建聚合，不生成新业务标识或领域事件。
     * @param id 标准UUID
     * @param kind 类别
     * @param code 编码
     * @param name 名称
     * @param baseUnit 基本单位
     * @param version 持久化版本
     * @return 已持久化聚合
     * @throws IllegalArgumentException UUID、版本或数据非法
     * @apiNote 示例：MasterData.restore(id, kind, code, name, unit, version)。
     */
    public static MasterData restore(String id, Kind kind, String code, String name, String baseUnit, long version) {
        String canonical = UUID.fromString(ValidationHelper.requireNonBlank(id, "id")).toString();
        if (!canonical.equalsIgnoreCase(id)) throw new IllegalArgumentException("id必须为标准UUID");
        return new MasterData(canonical, kind, code, name, baseUnit, version);
    }

    /**
     * 修改名称，不提供编码、类别或基本单位修改入口。
     * @param name 新名称
     * @throws IllegalArgumentException 名称非法
     * @apiNote 示例：material.rename("冷轧钢板")。
     */
    public void rename(String name) { this.name = text(name, 200, "name"); }

    /**
     * 在写用例中校验客户端观察到的版本，数据库保存仍须再次比较版本。
     * @param expectedVersion 客户端期望版本
     * @throws StratumException 版本过期或聚合未持久化
     * @apiNote 示例：material.requireVersion(command.getVersion())。
     */
    public void requireVersion(long expectedVersion) {
        if (getVersion() == null || getVersion() != expectedVersion) throw new StratumException(CommonErrorCode.CONFLICT);
    }

    /**
     * 防止使用另一主数据类别的标识跨接口操作。
     * @param expectedKind 用例声明的类别
     * @throws StratumException 实际类别不匹配时返回不存在
     * @apiNote 示例：material.requireKind(Kind.MATERIAL)。
     */
    public void requireKind(Kind expectedKind) {
        if (kind != Objects.requireNonNull(expectedKind, "expectedKind")) throw new StratumException(CommonErrorCode.NOT_FOUND);
    }

    /**
     * 仓储成功写入后推进版本，禁止重置或跨越版本。
     * @param version 新增为0，更新为当前版本加1
     * @throws IllegalArgumentException 新版本不是下一版本
     * @apiNote 示例：仓储调用aggregate.markPersisted(0)。
     */
    public void markPersisted(long version) {
        long next = getVersion() == null ? 0 : Math.incrementExact(getVersion());
        if (version != next) throw new IllegalArgumentException("持久化版本必须按顺序推进");
        setVersion(version);
    }

    /**
     * 获取固定类别。
     * @return 主数据类别
     * @apiNote 示例：aggregate.getKind()。
     */
    public Kind getKind() { return kind; }

    /**
     * 获取固定编码。
     * @return 同类业务编码
     * @apiNote 示例：aggregate.getCode()。
     */
    public String getCode() { return code; }

    /**
     * 获取当前名称。
     * @return 名称
     * @apiNote 示例：aggregate.getName()。
     */
    public String getName() { return name; }

    /**
     * 获取物料基本单位。
     * @return 物料基本单位，其他类别为空
     * @apiNote 示例：material.getBaseUnit()。
     */
    public String getBaseUnit() { return baseUnit; }

    /**
     * 复用通用空白校验并增加主数据存储边界，拒绝控制字符。
     * @param value 输入文本
     * @param maximumLength 最大UTF-16字符数
     * @param field 字段名
     * @return 去首尾空白的文本
     * @throws IllegalArgumentException 为空、过长或含控制字符
     */
    private static String text(String value, int maximumLength, String field) {
        String text = ValidationHelper.requireNonBlank(value, field);
        if (text.length() > maximumLength || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + "超长或包含控制字符");
        }
        return text;
    }
}
