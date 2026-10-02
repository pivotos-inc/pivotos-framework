package com.pivotos.cloud.sentinel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S133 V3 spike —— Cloud Starter ④：流控（对齐 Sentinel 的核心语义）。
 *
 * <p>用「每秒滑动窗口 + 原子计数」实现，不做熔断/热点参数。<b>默认阈值极大（等于不限流）</b>，
 * 只有在显式配置 {@code pivotos.cloud.sentinel.qps} 时才真正生效 —— 保证「引了不影响单体形态」。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "pivotos.cloud.sentinel", name = "enabled", havingValue = "true")
public class SentinelAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SentinelAutoConfiguration.class);

    /** 显式 Environment 绑定（Boot 4.1.0 下 binder 对命令行参数失效，见 openfeign 同注释） */
    @Bean
    public SentinelProps sentinelProps(org.springframework.core.env.Environment env) {
        SentinelProps p = new SentinelProps();
        String prefix = "pivotos.cloud.sentinel.";
        p.setEnabled(env.getProperty(prefix + "enabled", Boolean.class, false));
        p.setQps(env.getProperty(prefix + "qps", Integer.class, 0));
        p.setUrlPattern(env.getProperty(prefix + "url-pattern", "/*"));
        return p;
    }

    @ConfigurationProperties(prefix = "pivotos.cloud.sentinel")
    public static class SentinelProps {
        private boolean enabled = false;
        /** 每秒允许通过的请求数；<=0 表示不限流 */
        private int qps = 0;
        /** 是否只对 /api/** 生效 */
        private String urlPattern = "/*";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getQps() {
            return qps;
        }

        public void setQps(int qps) {
            this.qps = qps;
        }

        public String getUrlPattern() {
            return urlPattern;
        }

        public void setUrlPattern(String urlPattern) {
            this.urlPattern = urlPattern;
        }
    }

    @Bean
    public FilterRegistrationBean<Filter> cloudSentinelRateLimitFilter(SentinelProps props) {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(new RateLimitFilter(props));
        bean.setName("pivotos-cloud-sentinel-limit");
        bean.addUrlPatterns(props.getUrlPattern());
        bean.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE + 10);
        log.info("[CLOUD][sentinel] 流控已装配：qps={}（<=0 表示不限流），pattern={}",
                props.getQps(), props.getUrlPattern());
        return bean;
    }

    static class RateLimitFilter implements Filter {
        private final SentinelProps props;
        private final AtomicInteger windowCount = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        RateLimitFilter(SentinelProps props) {
            this.props = props;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (props.getQps() > 0 && request instanceof HttpServletRequest req
                    && response instanceof HttpServletResponse resp) {
                long now = System.currentTimeMillis();
                if (now - windowStart >= 1000) {
                    synchronized (this) {
                        if (now - windowStart >= 1000) {
                            windowStart = now;
                            windowCount.set(0);
                        }
                    }
                }
                if (windowCount.incrementAndGet() > props.getQps()) {
                    resp.setStatus(429);
                    resp.setContentType("application/json;charset=UTF-8");
                    resp.getWriter().write("{\"code\":429,\"msg\":\"[CLOUD-SENTINEL] 触发限流，请稍后再试\"}");
                    log.warn("[CLOUD-SENTINEL] {} 触发限流 qps={}", req.getRequestURI(), props.getQps());
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
