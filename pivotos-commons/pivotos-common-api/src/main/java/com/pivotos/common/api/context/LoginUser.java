package com.pivotos.common.api.context;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录用户上下文载体：由 starter-auth 登录拦截器绑定，
 * 全链路通过 LoginContext 读取。
 */
public class LoginUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID（雪花） */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 账号体系：sys-user / app-user / wx-mini-user */
    private String accountType;

    /** 租户 ID，单租户模式为 null */
    private Long tenantId;

    public LoginUser() {
    }

    public LoginUser(Long userId, String username, String accountType, Long tenantId) {
        this.userId = userId;
        this.username = username;
        this.accountType = accountType;
        this.tenantId = tenantId;
    }

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

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}
