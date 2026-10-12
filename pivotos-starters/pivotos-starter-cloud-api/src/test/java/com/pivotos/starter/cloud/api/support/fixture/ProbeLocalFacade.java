package com.pivotos.starter.cloud.api.support.fixture;

import com.pivotos.starter.cloud.api.support.FacadeProxySupport;

/**
 * 被远程代理替换后「本地实现」的留存形态：名字带 {@code $Local} 后缀。
 *
 * <p>返回 LOCAL 前缀是为了让断言能一眼看出「反射到底打到哪个 Bean」——
 * 打到代理上的形态是自调用死循环（代理 → HTTP → 本进程代理 → …），
 * 单测里若不刻意区分就会伪装成正常返回。
 */
public class ProbeLocalFacade implements ProbeFacade {

    public static final String BEAN_NAME = "probeFacade" + FacadeProxySupport.LOCAL_SUFFIX;

    @Override
    public String greet(String name) {
        return "LOCAL:hello," + name;
    }

    @Override
    public String greet(String name, String suffix) {
        return "LOCAL:hello," + name + suffix;
    }

    @Override
    public String ping() {
        return "LOCAL:pong";
    }
}
