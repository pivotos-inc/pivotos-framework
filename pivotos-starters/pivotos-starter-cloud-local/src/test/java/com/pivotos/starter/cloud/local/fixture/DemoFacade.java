package com.pivotos.starter.cloud.local.fixture;

/** 测试用契约接口：模拟业务的 {@code XxxFacade}（跨进程调用的对象） */
public interface DemoFacade {

    String greet(String name);

    int add(int a, int b);

    /**
     * 回显「执行时刻本进程看到的上下文」：{@code tenantId:userId:username}（缺失为 {@code null}）。
     *
     * <p><b>为什么不是直接 {@code return "hello"}</b>：要验的是「入站恢复是否真的落地到 ScopedValue」，
     * 返回值必须<b>由上下文决定</b>——否则没恢复也会返回同样的字符串，测试形同虚设。
     */
    String whoAmI();

    /** 回显执行线程名：用于证明下游确实跑在另一个线程上（排除 ScopedValue 泄漏式假阳性） */
    String threadName();
}
