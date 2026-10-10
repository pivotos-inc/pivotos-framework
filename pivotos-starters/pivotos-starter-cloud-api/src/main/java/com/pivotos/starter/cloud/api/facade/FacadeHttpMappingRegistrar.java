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
        Method method = ReflectionUtils.findMethod(FacadeHttpHandler.class, "invoke",
            String.class, String.class, String.class, jakarta.servlet.http.HttpServletRequest.class);
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
