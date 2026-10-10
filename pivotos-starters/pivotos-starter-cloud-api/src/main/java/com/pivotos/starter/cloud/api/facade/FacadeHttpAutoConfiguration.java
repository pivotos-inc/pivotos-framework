package com.pivotos.starter.cloud.api.facade;

import com.pivotos.starter.cloud.api.config.CloudProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Facade HTTP 暴露的自动配置（方案 C：运行时适配，契约层零改动）。
 *
 * <p>四条装配边界，与 S133 三条铁律同源：
 * <ol>
 *   <li>仅当 {@code pivotos.cloud.facade-http.enabled=true} 时装配（默认 false = 单体形态零行为）；</li>
 *   <li>必须是 Servlet Web 应用且 classpath 有 MVC 的 {@code RequestMappingHandlerMapping}；</li>
 *   <li>映射由 {@link FacadeHttpMappingRegistrar} 编程式注册，<b>不靠组件扫描</b>——
 *       否则宿主的扫描范围（admin-server 扫 {@code com.pivotos..}）会让开关失效；</li>
 *   <li>端点已开但没配 {@code pivotos.cloud.context.internal-token} → 启动期 FAIL-FAST。</li>
 * </ol>
 *
 * <p><b>为什么第 4 条必须是启动失败而不是「运行时 403」</b>：配置写漏时，运行时 403 会让调用方
 * 以为是网络问题反复重试；启动期炸掉则立刻把「开关开了一半」暴露出来——
 * 与 {@code LocalCloudAutoConfiguration#facadeRpcServlet} 完全同一口径。
 */
@AutoConfiguration(after = org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class)
@EnableConfigurationProperties(CloudProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping")
@ConditionalOnProperty(prefix = "pivotos.cloud.facade-http", name = "enabled", havingValue = "true")
public class FacadeHttpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FacadeHttpAutoConfiguration.class);

    @Bean
    public FacadeHttpHandler facadeHttpHandler(ApplicationContext context, CloudProperties cloudProps) {
        String token = cloudProps.getContext().getInternalToken();
        if (token == null || token.isBlank()) {
            // 端点已显式开启却没配凭证 = 开了个按 URL 反射调用的后门，必须响（同 local 通道口径）
            throw new IllegalStateException(
                "[CLOUD][facade-http] FAIL-FAST：pivotos.cloud.facade-http.enabled=true 必须同时配置 "
                    + "pivotos.cloud.context.internal-token，否则 "
                    + cloudProps.getFacadeHttp().getPath() + " 将成为公开反射后门");
        }
        return new FacadeHttpHandler(context, cloudProps);
    }

    @Bean
    public FacadeHttpMappingRegistrar facadeHttpMappingRegistrar(RequestMappingHandlerMapping handlerMapping,
                                                                 FacadeHttpHandler handler,
                                                                 CloudProperties cloudProps) {
        String path = cloudProps.getFacadeHttp().getPath();
        log.info("[CLOUD][facade-http] Facade HTTP 端点已装配：{}/{{iface}}/{{method}}"
                + "（强制内部凭证，白名单 {} 项：{}）",
            path, cloudProps.getFacadeHttp().getInterfaces().size(),
            String.join(", ", cloudProps.getFacadeHttp().getInterfaces()));
        return new FacadeHttpMappingRegistrar(handlerMapping, handler, path);
    }
}
