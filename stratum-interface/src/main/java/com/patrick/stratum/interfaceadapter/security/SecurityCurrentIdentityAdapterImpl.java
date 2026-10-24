package com.patrick.stratum.interfaceadapter.security;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 将安全上下文适配到应用身份端口，只接受专用的已验证Access认证类型。 */
@Component
public class SecurityCurrentIdentityAdapterImpl implements CurrentIdentity {
    /** {@inheritDoc} */
    @Override public Snapshot require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof VerifiedAccessAuthentication verified) || !verified.isAuthenticated()) {
            throw new StratumException(CommonErrorCode.UNAUTHORIZED);
        }
        return verified.getPrincipal();
    }
}
