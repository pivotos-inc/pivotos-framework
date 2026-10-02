package com.pivotos.cloud.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * S133 V3 spike —— Cloud Starter ③：统一入口（对齐 Spring Cloud Gateway 的边缘职责）。
 *
 * <p>spike 期只做三件可逆的事：注入 {@code X-Edge-Gateway} / {@code X-Edge-Request-Id} 头、
 * 可选的前缀剥离转发。默认参数不会改变任何既有请求行为，保证「引了也不影响单体形态」。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "pivotos.cloud.gateway", name = "enabled", havingValue = "true")
public class GatewayAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayAutoConfiguration.class);

    /** 显式 Environment 绑定（Boot 4.1.0 下 binder 对命令行参数失效，见 openfeign 同注释） */
    @Bean
    public GatewayProps gatewayProps(org.springframework.core.env.Environment env) {
        GatewayProps p = new GatewayProps();
        String prefix = "pivotos.cloud.gateway.";
        p.setEnabled(env.getProperty(prefix + "enabled", Boolean.class, false));
        p.setStripPrefix(env.getProperty(prefix + "strip-prefix", ""));
        p.setHeader(env.getProperty(prefix + "header", "X-Edge-Gateway"));
        p.setInstanceId(env.getProperty(prefix + "instance-id", "pivotos-edge-1"));
        return p;
    }

    @ConfigurationProperties(prefix = "pivotos.cloud.gateway")
    public static class GatewayProps {
        private boolean enabled = false;
        /** 需要被剥离的前缀（如 /gw）；留空则不做路径改写 */
        private String stripPrefix = "";
        private String header = "X-Edge-Gateway";
        private String instanceId = "pivotos-edge-1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStripPrefix() {
            return stripPrefix;
        }

        public void setStripPrefix(String stripPrefix) {
            this.stripPrefix = stripPrefix;
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }
    }

    @Bean
    public FilterRegistrationBean<Filter> cloudGatewayEdgeFilter(GatewayProps props) {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(new EdgeFilter(props));
        bean.setName("pivotos-cloud-gateway-edge");
        bean.addUrlPatterns("/*");
        bean.setOrder(FilterRegistrationBean.LOWEST_PRECEDENCE - 100);
        log.info("[CLOUD][gateway] 统一入口已装配：instance={}, strip-prefix={}",
                props.getInstanceId(), StringUtils.hasText(props.getStripPrefix()) ? props.getStripPrefix() : "（未启用）");
        return bean;
    }

    static class EdgeFilter implements Filter {
        private final GatewayProps props;

        EdgeFilter(GatewayProps props) {
            this.props = props;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (request instanceof HttpServletRequest req && response instanceof HttpServletResponse resp) {
                String rid = UUID.randomUUID().toString().substring(0, 8);
                resp.setHeader(props.getHeader(), props.getInstanceId());
                resp.setHeader("X-Edge-Request-Id", rid);
                String path = req.getRequestURI();
                if (StringUtils.hasText(props.getStripPrefix()) && path.startsWith(props.getStripPrefix())) {
                    // 边缘转发：剥离前缀后 internal forward，业务代码无感知
                    String target = path.substring(props.getStripPrefix().length());
                    req.getRequestDispatcher(target).forward(request, response);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
