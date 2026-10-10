package com.pivotos.starter.cloud.local.fixture;

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
}
