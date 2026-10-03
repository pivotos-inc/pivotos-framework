package com.pivotos.starter.cloud.sc.fixture;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 真实 Feign 链路测试用契约。
 *
 * <p>与业务 Facade（{@code IUserFacade} 等）不同，它<b>带 Spring MVC 注解</b>——
 * Feign 的 Contract 要靠注解才知道请求路径与方法。这也顺带暴露了一个待办：
 * 业务 Facade 契约目前没有 MVC 注解，直接拿去做 Feign 客户端是建不出路径的。
 */
public interface ProbedFacade {

    @RequestMapping(method = RequestMethod.GET, value = "/__probe/echo", produces = MediaType.TEXT_PLAIN_VALUE)
    String echo(@RequestParam("v") String v);
}
