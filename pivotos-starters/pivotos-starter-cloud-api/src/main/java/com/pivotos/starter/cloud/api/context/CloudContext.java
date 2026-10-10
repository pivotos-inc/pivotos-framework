package com.pivotos.starter.cloud.api.context;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * 跨进程传播的上下文快照（纯数据、可序列化、不含任何领域对象）。
 *
 * <p>只装「跨进程后仍成立」的最小集合：租户、身份、链路。
 * 刻意<b>不装</b> token 原文、Request/Response、Spring 类型、领域实体——
 * 前两者会造成凭证扩散与内存泄漏，后两者让契约与实现同生共死（ArchUnit C3 同款理由）。
 */
public class CloudContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 空上下文（无租户、未登录、无链路）的共享实例 */
    public static final CloudContext EMPTY = new CloudContext(null, null, null, null, null);

    private final Long tenantId;
    private final Long userId;
    private final String username;
    private final String accountType;
    private final String traceId;

    public CloudContext(Long tenantId, Long userId, String username, String accountType, String traceId) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.username = username;
        this.accountType = accountType;
        this.traceId = traceId;
    }

    /** 仅租户的上下文（最常见的内部调用形态） */
    public static CloudContext ofTenant(Long tenantId) {
        return new CloudContext(tenantId, null, null, null, null);
    }

    /** 链路上下文（只延续 traceId） */
    public static CloudContext ofTrace(String traceId) {
        return new CloudContext(null, null, null, null, traceId);
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getAccountType() {
        return accountType;
    }

    public String getTraceId() {
        return traceId;
    }

    /** 是否没有任何可传播内容 */
    public boolean isEmpty() {
        return tenantId == null && userId == null && username == null && traceId == null;
    }

    /** 是否携带身份（有其一即视为携带，避免只传 username 就被判为未传播） */
    public boolean hasIdentity() {
        return userId != null || username != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CloudContext other)) {
            return false;
        }
        return Objects.equals(tenantId, other.tenantId)
            && Objects.equals(userId, other.userId)
            && Objects.equals(username, other.username)
            && Objects.equals(accountType, other.accountType)
            && Objects.equals(traceId, other.traceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, userId, username, accountType, traceId);
    }

    @Override
    public String toString() {
        return "CloudContext{tenantId=" + tenantId
            + ", userId=" + userId
            + ", username=" + username
            + ", accountType=" + accountType
            + ", traceId=" + traceId + '}';
    }
}
