package com.pivotos.starter.core.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JSON 引擎配置
 */
@ConfigurationProperties(prefix = "pivotos.json")
public class JsonProperties {

    /**
     * 全局 JSON 引擎：fastjson2（默认）/ jackson（兼容问题时的全局回退开关）
     */
    private String engine = "fastjson2";

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }
}
