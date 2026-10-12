package com.pivotos.starter.cloud.api.support.fixture;

/**
 * 模拟「被替换到原 Bean 名上的远程代理」：它不该被 RPC/HTTP 端点再命中一次，
 * 否则就是代理 → HTTP → 代理的自调用死循环。
 */
public class ProbeProxyFacade implements ProbeFacade {

    public static final String BEAN_NAME = "probeFacade";

    @Override
    public String greet(String name) {
        return "PROXY:hello," + name;
    }

    @Override
    public String greet(String name, String suffix) {
        return "PROXY:hello," + name + suffix;
    }

    @Override
    public String ping() {
        return "PROXY:pong";
    }
}
