package com.pivotos.starter.core.context;

import com.pivotos.common.api.context.ContextFacade;
import com.pivotos.common.api.context.LoginUser;

/**
 * ContextFacade 的 ScopedValue 实现：供外部模块读取上下文，
 * 消费方只依赖 common-api 接口，不感知实现机制。
 */
public class ScopedValueContextFacade implements ContextFacade {

    @Override
    public Long getLoginUserId() {
        return LoginContext.getUserId();
    }

    @Override
    public String getLoginUsername() {
        return LoginContext.getUsername();
    }

    @Override
    public Long getTenantId() {
        LoginUser user = LoginContext.get();
        Long tenantId = TenantContext.get();
        return tenantId != null ? tenantId : (user == null ? null : user.getTenantId());
    }

    @Override
    public String getTraceId() {
        return TraceContext.get();
    }
}
