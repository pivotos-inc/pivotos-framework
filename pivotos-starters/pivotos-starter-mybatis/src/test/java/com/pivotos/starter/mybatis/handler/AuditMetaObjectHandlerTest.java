package com.pivotos.starter.mybatis.handler;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 审计填充单测：验证 LoginContext / TenantContext 取值与未登录兜底
 */
class AuditMetaObjectHandlerTest {

    private final AuditMetaObjectHandler handler = new AuditMetaObjectHandler();

    static class TestDO extends TenantBaseDO {
    }

    @Test
    void insertFillWithLoginAndTenant() {
        TestDO entity = new TestDO();
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        LoginUser user = new LoginUser(42L, "admin", "sys-user", 7L);
        ScopedValue.where(LoginContext.KEY, user)
                .where(TenantContext.KEY, 7L)
                .run(() -> handler.insertFill(metaObject));

        assertEquals(42L, entity.getCreateBy());
        assertEquals(42L, entity.getUpdateBy());
        assertNotNull(entity.getCreateTime());
        assertNotNull(entity.getUpdateTime());
        assertEquals(0, entity.getDeleted());
        assertEquals(7L, entity.getTenantId());
    }

    @Test
    void insertFillWithoutLoginShouldFallbackToSystem() {
        TestDO entity = new TestDO();
        handler.insertFill(SystemMetaObject.forObject(entity));

        assertEquals(0L, entity.getCreateBy());
        assertEquals(0, entity.getDeleted());
        // 无租户上下文不填充 tenantId
        assertNull(entity.getTenantId());
    }

    @Test
    void updateFillOnlyTouchUpdateFields() {
        TestDO entity = new TestDO();
        handler.updateFill(SystemMetaObject.forObject(entity));

        assertNotNull(entity.getUpdateTime());
        assertEquals(0L, entity.getUpdateBy());
        assertNull(entity.getCreateTime());
    }
}
