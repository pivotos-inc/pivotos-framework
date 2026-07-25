package com.pivotos.common.api.context;

/**
 * 上下文门面：供外部模块读取登录人 / 租户 / 链路信息。
 * 实现由 pivotos-starter-core 基于 ScopedValue 提供，
 * 消费方只依赖本接口，不感知实现机制。
 */
public interface ContextFacade {

    /**
     * 当前登录用户 ID，未登录返回 null
     */
    Long getLoginUserId();

    /**
     * 当前登录用户名，未登录返回 null
     */
    String getLoginUsername();

    /**
     * 当前租户 ID，无租户上下文返回 null
     */
    Long getTenantId();

    /**
     * 当前链路追踪 ID
     */
    String getTraceId();
}
