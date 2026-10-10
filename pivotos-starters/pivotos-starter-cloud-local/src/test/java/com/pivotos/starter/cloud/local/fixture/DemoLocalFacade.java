package com.pivotos.starter.cloud.local.fixture;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import org.springframework.stereotype.Component;

/** DemoFacade 的本地实现（测试里以 {@code $Local} 别名注册，模拟真实替换后的形态） */
@Component("demoFacade")
public class DemoLocalFacade implements DemoFacade {

    @Override
    public String greet(String name) {
        return "hello," + name;
    }

    @Override
    public int add(int a, int b) {
        return a + b;
    }

    @Override
    public String whoAmI() {
        Long tenantId = TenantContext.get();
        LoginUser user = LoginContext.get();
        return (tenantId == null ? "null" : String.valueOf(tenantId))
            + ":" + (user == null || user.getUserId() == null ? "null" : String.valueOf(user.getUserId()))
            + ":" + (user == null || user.getUsername() == null ? "null" : user.getUsername());
    }

    @Override
    public String threadName() {
        return Thread.currentThread().getName();
    }
}
