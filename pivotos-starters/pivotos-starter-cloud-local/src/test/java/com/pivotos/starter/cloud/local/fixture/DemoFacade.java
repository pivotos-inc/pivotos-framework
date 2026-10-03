package com.pivotos.starter.cloud.local.fixture;

/** 测试用契约接口：模拟业务的 {@code XxxFacade}（跨进程调用的对象） */
public interface DemoFacade {

    String greet(String name);

    int add(int a, int b);
}
