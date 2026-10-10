package com.pivotos.starter.cloud.alibaba.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Cloud Alibaba 通道配置（{@code pivotos.cloud.alibaba.*}）。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.cloud.alibaba")
public class AlibabaCloudProperties {

    /** Sentinel 保护是否启用（启用后按 rules 加载流量规则） */
    private boolean sentinelEnabled = false;

    /**
     * 流量规则：{@code 资源名 -> QPS 阈值}。
     * 空 = 只装配 Guard，不主动限流（Guard 的 protect 退化成直通）。
     */
    private Map<String, Integer> rules = new LinkedHashMap<>();

    /** 资源名前缀（多实例共存时避免资源名撞车） */
    private String resourcePrefix = "pivotos";
}
