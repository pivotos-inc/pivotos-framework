package com.pivotos.starter.web.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CORS 配置
 */
@ConfigurationProperties(prefix = "pivotos.cors")
public class CorsProperties {

    /** 允许的源，默认全部（开发期）；生产环境务必收敛为显式域名 */
    private List<String> allowedOriginPatterns = List.of("*");

    /** 允许的方法 */
    private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");

    /** 预检缓存秒数 */
    private long maxAge = 3600;

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    public List<String> getAllowedMethods() {
        return allowedMethods;
    }

    public void setAllowedMethods(List<String> allowedMethods) {
        this.allowedMethods = allowedMethods;
    }

    public long getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(long maxAge) {
        this.maxAge = maxAge;
    }
}
