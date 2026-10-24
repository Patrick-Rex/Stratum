package com.patrick.stratum.domain.auth;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证管理准入和最后管理员保护在所有边界下拒绝不安全变更。 */
class AdministrationPolicyTest {
    /** 未启用或没有管理员角色的操作者不能管理账号与角色。 */
    @Test
    void requiresEnabledAdministrator() {
        assertDoesNotThrow(() -> AdministrationPolicy.requireAdministrator(true));
        assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class,
                () -> AdministrationPolicy.requireAdministrator(false)).getErrorCode());
    }

    /** 只在真实减少可用管理员时保护最后一人，非法计数不能掩盖错误。 */
    @Test
    void preservesLastEnabledAdministrator() {
        for (long count : new long[]{0, 1}) {
            assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class,
                    () -> AdministrationPolicy.requireAdministratorRemaining(count, true)).getErrorCode());
            assertDoesNotThrow(() -> AdministrationPolicy.requireAdministratorRemaining(count, false));
        }
        assertDoesNotThrow(() -> AdministrationPolicy.requireAdministratorRemaining(2, true));
        assertDoesNotThrow(() -> AdministrationPolicy.requireAdministratorRemaining(Long.MAX_VALUE, true));
        assertThrows(IllegalArgumentException.class, () -> AdministrationPolicy.requireAdministratorRemaining(-1, false));
    }
}
