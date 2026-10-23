package com.patrick.stratum.domain.masterdata;

import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证用户确认的主数据不变量，不连接框架或数据库。 */
class MasterDataTest {
    /** 四类主数据均由领域生成UUID，只有物料使用基本单位。 */
    @Test
    void createsAllKindsWithDomainIdentifiers() {
        for (var kind : MasterData.Kind.values()) {
            var value = MasterData.create(kind, " A001 ", " 名称 ", kind == MasterData.Kind.MATERIAL ? "千克" : null);
            assertEquals(value.getId(), UUID.fromString(value.getId()).toString());
            assertEquals(kind, value.getKind());
            assertEquals("A001", value.getCode());
            assertEquals("名称", value.getName());
            assertNull(value.getVersion());
        }
        assertNotEquals(material().getId(), material().getId());
    }

    /** 改名保留编码、类别和物料基本单位。 */
    @Test
    void renamePreservesFixedFields() {
        var value = material();
        value.rename("冷轧钢板");
        assertEquals("冷轧钢板", value.getName());
        assertEquals("M001", value.getCode());
        assertEquals("千克", value.getBaseUnit());
        assertEquals(MasterData.Kind.MATERIAL, value.getKind());
        assertThrows(IllegalArgumentException.class, () -> value.rename(" "));
        assertEquals("冷轧钢板", value.getName());
    }

    /** 物料单位必填，其他主数据不能混入物料字段。 */
    @Test
    void rejectsUnitsThatDoNotMatchKind() {
        assertThrows(IllegalArgumentException.class, () -> MasterData.create(MasterData.Kind.MATERIAL, "M", "名称", null));
        for (var kind : new MasterData.Kind[]{MasterData.Kind.CUSTOMER, MasterData.Kind.SUPPLIER, MasterData.Kind.WAREHOUSE}) {
            assertThrows(IllegalArgumentException.class, () -> MasterData.create(kind, "C", "名称", "千克"));
        }
    }

    /** 存储长度和可见文本边界阻止非法快照进入持久化流程。 */
    @Test
    void rejectsInvalidTextAndSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> MasterData.create(MasterData.Kind.MATERIAL, "M".repeat(65), "名称", "千克"));
        assertThrows(IllegalArgumentException.class, () -> material().rename("名".repeat(201)));
        assertThrows(IllegalArgumentException.class, () -> material().rename("名称\n换行"));
        assertThrows(IllegalArgumentException.class, () -> MasterData.restore("1-1-1-1-1", MasterData.Kind.MATERIAL, "M", "名称", "千克", 0));
        assertThrows(IllegalArgumentException.class, () -> MasterData.restore(UUID.randomUUID().toString(), MasterData.Kind.MATERIAL, "M", "名称", "千克", -1));
    }

    /** 过期版本与跨类别操作分别按冲突和不存在拒绝，版本只允许顺序推进。 */
    @Test
    void protectsVersionAndKind() {
        var value = material();
        assertThrows(StratumException.class, () -> value.requireVersion(0));
        value.markPersisted(0);
        assertDoesNotThrow(() -> value.requireVersion(0));
        value.markPersisted(1);
        var conflict = assertThrows(StratumException.class, () -> value.requireVersion(0));
        assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode());
        assertThrows(IllegalArgumentException.class, () -> value.markPersisted(3));
        var wrongKind = assertThrows(StratumException.class, () -> value.requireKind(MasterData.Kind.CUSTOMER));
        assertEquals(CommonErrorCode.NOT_FOUND, wrongKind.getErrorCode());
    }

    /** 事件保存独立快照及版本，删除事件为更高版本，不能从未保存聚合制造事件。 */
    @Test
    void capturesImmutableEventSnapshots() {
        var value = material();
        assertThrows(IllegalArgumentException.class, () -> new MasterDataChanged(value, MasterDataChanged.Change.CREATED));
        value.markPersisted(0);
        var created = new MasterDataChanged(value, MasterDataChanged.Change.CREATED);
        value.rename("新名称");
        value.markPersisted(1);
        assertEquals("钢板", created.getName());
        assertEquals(0, created.getVersion());
        assertEquals(2, new MasterDataChanged(value, MasterDataChanged.Change.DELETED).getVersion());
        assertEquals(value.getId(), created.getAggregateId());
    }

    /**
     * 构造独立物料测试聚合。
     * @return 新物料
     */
    private MasterData material() { return MasterData.create(MasterData.Kind.MATERIAL, "M001", "钢板", "千克"); }
}
