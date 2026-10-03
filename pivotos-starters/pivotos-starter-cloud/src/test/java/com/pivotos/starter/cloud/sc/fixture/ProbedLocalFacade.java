package com.pivotos.starter.cloud.sc.fixture;

/** {@link ProbedFacade} 的进程内实现：被替换后以 {@code $Local} 保留，用于断言替换与降级 */
public class ProbedLocalFacade implements ProbedFacade {

    @Override
    public String echo(String v) {
        return "local:" + v;
    }
}
