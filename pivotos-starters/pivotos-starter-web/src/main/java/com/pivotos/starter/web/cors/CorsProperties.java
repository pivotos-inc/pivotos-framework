package com.pivotos.starter.web.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CORS 配置。
 * <p>
 * 代理同源模式下（前端 baseURL='/api'，经 Vite proxy / Nginx 反代），浏览器请求与后端同源，
 * 不发送 Origin 头、不触发预检，此 CORS 配置处于休眠状态。
 * 保留以兼容直连后端调试（如 Postman、浏览器裸连 8080）等非代理场景。
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
