package com.pivotos.starter.redis.cache;

import com.pivotos.starter.core.context.TenantContext;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * 缓存 key 生成器（规范：key 必须含租户维度）。
 * 用法：@Cacheable(cacheNames = "user", keyGenerator = "tenantCacheKeyGenerator")
 * 生成格式：tenant:{tenantId}:{类名}.{方法名}:{参数串}；无租户上下文时租户段为 0。
 */
public class TenantCacheKeyGenerator implements KeyGenerator {

    /** 单租户（无租户上下文）时的固定段 */
    private static final String NO_TENANT = "0";

    @Override
    public Object generate(Object target, Method method, Object... params) {
        Long tenantId = TenantContext.get();
        String tenant = tenantId == null ? NO_TENANT : String.valueOf(tenantId);
        return "tenant:" + tenant + ":"
                + target.getClass().getSimpleName() + "." + method.getName() + ":"
                + StringUtils.arrayToDelimitedString(params, "_");
    }
}
