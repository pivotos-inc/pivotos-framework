package com.pivotos.system.domain.vo;

import java.io.Serial;
import java.io.Serializable;

/**
 * 在线用户视图对象（数据源自 Sa-Token 会话，非持久化表）。
 */
public class OnlineUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** Token 值（掩码后，仅用于前端展示） */
    private String tokenValue;

    /** Token 明文（不展示，供强退操作使用） */
    private String rawToken;

    /** 登录 IP */
    private String ipAddr;

    /** 登录时间 */
    private String loginTime;

    /** 最后活跃时间（近似值，Token 未启用 activity-timeout） */
    private String lastActiveTime;

    /** Token 剩余有效期（秒），-1 表示持久 */
    private Long tokenTtl;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public void setTokenValue(String tokenValue) {
        this.tokenValue = tokenValue;
    }

    public String getRawToken() {
        return rawToken;
    }

    public void setRawToken(String rawToken) {
        this.rawToken = rawToken;
    }

    public String getIpAddr() {
        return ipAddr;
    }

    public void setIpAddr(String ipAddr) {
        this.ipAddr = ipAddr;
    }

    public String getLoginTime() {
        return loginTime;
    }

    public void setLoginTime(String loginTime) {
        this.loginTime = loginTime;
    }

    public String getLastActiveTime() {
        return lastActiveTime;
    }

    public void setLastActiveTime(String lastActiveTime) {
        this.lastActiveTime = lastActiveTime;
    }

    public Long getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Long tokenTtl) {
        this.tokenTtl = tokenTtl;
    }
}
