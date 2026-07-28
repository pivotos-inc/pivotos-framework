package com.pivotos.system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信小程序配置（{@code pivotos.wechat.miniapp.*}）。
 * appid/secret 缺省即未配置，/mini/auth/* 端点报 2080，不影响其余功能。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.wechat.miniapp")
public class WechatMiniProperties {

    /** 小程序 AppID */
    private String appid;

    /** 小程序 AppSecret */
    private String secret;

    /** 是否已完成最小配置 */
    public boolean configured() {
        return appid != null && !appid.isBlank() && secret != null && !secret.isBlank();
    }
}
