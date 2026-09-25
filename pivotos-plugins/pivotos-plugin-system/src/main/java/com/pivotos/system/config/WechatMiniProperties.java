package com.pivotos.system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 微信小程序配置（{@code pivotos.wechat.miniapp.*}）。
 * <p>支持多小程序：默认 key 为 {@code default}，枢磐·智域 key 为 {@code mind}。
 * 兼容旧版单小程序平铺配置（appid/secret），未命名时映射到 default。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.wechat.miniapp")
public class WechatMiniProperties {

    /** 默认小程序 AppID（兼容旧配置） */
    private String appid;

    /** 默认小程序 AppSecret（兼容旧配置） */
    private String secret;

    /** 多小程序配置（key = 应用标识） */
    private Map<String, MiniAppConfig> configs = new HashMap<>();

    /**
     * 获取指定应用的小程序配置，不存在则返回 null
     *
     * @param name 应用标识（default / mind）
     */
    public MiniAppConfig getConfig(String name) {
        MiniAppConfig named = configs.get(name);
        if (named != null && named.configured()) {
            return named;
        }
        // 兼容旧配置：default 回退到顶层 appid/secret
        if ("default".equals(name) && hasLegacyConfig()) {
            MiniAppConfig legacy = new MiniAppConfig();
            legacy.setAppid(appid);
            legacy.setSecret(secret);
            return legacy;
        }
        return null;
    }

    /** 默认小程序是否已配置 */
    public boolean configured() {
        return getConfig("default") != null;
    }

    /** 指定小程序是否已配置 */
    public boolean configured(String name) {
        return getConfig(name) != null;
    }

    private boolean hasLegacyConfig() {
        return appid != null && !appid.isBlank() && secret != null && !secret.isBlank();
    }

    /**
     * 单个小应用配置
     */
    @Data
    public static class MiniAppConfig {

        /** 小程序 AppID */
        private String appid;

        /** 小程序 AppSecret */
        private String secret;

        /** 是否已完成最小配置 */
        public boolean configured() {
            return appid != null && !appid.isBlank() && secret != null && !secret.isBlank();
        }
    }
}
