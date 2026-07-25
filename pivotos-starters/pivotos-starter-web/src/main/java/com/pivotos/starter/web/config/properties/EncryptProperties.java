package com.pivotos.starter.web.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 接口加解密配置
 */
@ConfigurationProperties(prefix = "pivotos.encrypt")
public class EncryptProperties {

    /** 总开关，默认关闭（S17 联调分阶段开启） */
    private boolean enabled = false;

    /** RSA 公钥有效期（分钟） */
    private int keyExpireMinutes = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getKeyExpireMinutes() {
        return keyExpireMinutes;
    }

    public void setKeyExpireMinutes(int keyExpireMinutes) {
        this.keyExpireMinutes = keyExpireMinutes;
    }
}
