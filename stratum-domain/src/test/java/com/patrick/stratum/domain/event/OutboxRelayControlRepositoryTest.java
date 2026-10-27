package com.patrick.stratum.domain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证共享控制状态与准入值对象的版本边界。 */
class OutboxRelayControlRepositoryTest {
    /** 非负版本与状态保存原值，最大版本不溢出。 */
    @Test
    void preservesStateAndAdmissionValues() {
        var state = new OutboxRelayControlRepository.State(true, 0);
        assertTrue(state.paused()); assertEquals(0, state.revision());
        var admission = new OutboxRelayControlRepository.Admission(true, Long.MAX_VALUE);
        assertTrue(admission.allowed()); assertEquals(Long.MAX_VALUE, admission.revision());
    }

    /** 负版本不能构造状态或准入结果。 */
    @Test
    void rejectsNegativeRevision() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxRelayControlRepository.State(false, -1));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRelayControlRepository.Admission(false, -1));
    }
}
