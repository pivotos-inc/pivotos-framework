package com.pivotos.starter.cloud.api.facade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.http.MediaType;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;

/**
 * 以编程方式把 {@link FacadeHttpHandler} 挂到 Spring MVC 的映射表上。
 *
 * <p><b>为什么不用 {@code @RestController} 让扫描去发现</b>：那样「端点是否注册」就不再由
 * {@code pivotos.cloud.facade-http.enabled} 决定，而是由宿主的组件扫描范围决定——
 * admin-server 扫 {@code com.pivotos..}，等于开关失效、单体形态凭空多出一个反射端点。
 * 编程式注册让开关重新成为唯一开关（本轮 ④ 踩坑 K1）。
 *
 * <p><b>为什么用 {@link SmartInitializingSingleton}</b>：注册动作必须发生在
 * {@code RequestMappingHandlerMapping} 完成自身初始化之后再触发，
 * 否则注册进去的映射可能被它随后的 {@code initHandlerMethods()} 覆盖掉。
 */
public class FacadeHttpMappingRegistrar implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(FacadeHttpMappingRegistrar.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final FacadeHttpHandler handler;
    private final String basePath;

    public FacadeHttpMappingRegistrar(RequestMappingHandlerMapping handlerMapping,
                                      FacadeHttpHandler handler,
                                      String basePath) {
        this.handlerMapping = handlerMapping;
        this.handler = handler;
        this.basePath = basePath;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 签名里没有 @RequestBody String：请求体由 handler 自己从 HttpServletRequest 读（见其注释）
        Method method = ReflectionUtils.findMethod(FacadeHttpHandler.class, "invoke",
            String.class, String.class, jakarta.servlet.http.HttpServletRequest.class);
        if (method == null) {
            throw new IllegalStateException("[CLOUD][facade-http] 找不到 FacadeHttpHandler#invoke 方法签名，注册中止");
        }
        RequestMappingInfo info = RequestMappingInfo
            .paths(normalize(basePath) + "/{iface}/{method}")
            .methods(RequestMethod.POST)
            .consumes(MediaType.APPLICATION_JSON_VALUE)
            .produces(MediaType.APPLICATION_JSON_VALUE)
            .build();
        handlerMapping.registerMapping(info, handler, method);
        log.info("[CLOUD][facade-http] 已注册 MVC 映射：POST {}/{{iface}}/{{method}}", normalize(basePath));
        inspectWhitelist();
    }

    /**
     * 启动期自检：白名单里的类名/方法名写错时，运行期的表现只有一个 404，
     * 排障只能靠猜。这里把「找不到类 / 找不到实现 / 找不到方法 / 同名同元数重载」
     * 四类问题在启动日志里直接点名。
     *
     * <p><b>放在 {@code afterSingletonsInstantiated()} 而不是 AutoConfiguration 里</b>：
     * 只有到这里容器里的业务 Bean 才全部就位，「本进程有没有这个 Facade 的实现」
     * 才问得准（在自动配置阶段问会得到一堆假阴性）。
     */
    private void inspectWhitelist() {
        for (String warning : handler.whitelist().inspect(handlerMapping.getApplicationContext())) {
            log.warn("[CLOUD][facade-http] 白名单自检：{}", warning);
        }
    }

    private String normalize(String path) {
        if (path == null || path.isBlank()) {
            return FacadeHttpConstants.BASE_PATH;
        }
        String trimmed = path.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }
}
