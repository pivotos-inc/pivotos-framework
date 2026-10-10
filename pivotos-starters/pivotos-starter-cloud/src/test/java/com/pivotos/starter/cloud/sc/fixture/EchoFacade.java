package com.pivotos.starter.cloud.sc.fixture;

/** 熔断测试用契约：模拟被 Feign 替换掉的 Facade 接口（进程内替身即可，不需要真的发 HTTP） */
public interface EchoFacade {

    String echo(String value);

    void ping();
}
