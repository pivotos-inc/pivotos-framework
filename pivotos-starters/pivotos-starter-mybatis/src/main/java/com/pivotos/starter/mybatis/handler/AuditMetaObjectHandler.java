package com.pivotos.starter.mybatis.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：创建/更新人取 LoginContext，租户取 TenantContext。
 * 未登录场景（如初始化脚本、系统任务）createBy/updateBy 填 0。
 * <p>实现说明：不使用 MP 的 strictInsertFill/strictUpdateFill——它们依赖
 * 运行期 TableInfo 注册表，在无 MP 上下文的场景（单测/系统任务）会 NPE。
 * 这里直接操作 MetaObject：insert 仅在字段为 null 时填充，update 总是覆盖。
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    /** 系统操作占位用户 ID */
    private static final Long SYSTEM_USER_ID = 0L;

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long userId = currentUserId();
        fillIfNull(metaObject, "createTime", now);
        fillIfNull(metaObject, "updateTime", now);
        fillIfNull(metaObject, "createBy", userId);
        fillIfNull(metaObject, "updateBy", userId);
        fillIfNull(metaObject, "deleted", 0);
        Long tenantId = TenantContext.get();
        if (tenantId != null) {
            fillIfNull(metaObject, "tenantId", tenantId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        fillAlways(metaObject, "updateTime", LocalDateTime.now());
        fillAlways(metaObject, "updateBy", currentUserId());
    }

    /**
     * 字段存在且当前为 null 才填充（insert 语义，保留调用方显式赋值）
     */
    private void fillIfNull(MetaObject metaObject, String fieldName, Object value) {
        if (metaObject.hasSetter(fieldName) && metaObject.getValue(fieldName) == null) {
            metaObject.setValue(fieldName, value);
        }
    }

    /**
     * 字段存在即覆盖（update 语义）
     */
    private void fillAlways(MetaObject metaObject, String fieldName, Object value) {
        if (metaObject.hasSetter(fieldName)) {
            metaObject.setValue(fieldName, value);
        }
    }

    private Long currentUserId() {
        Long userId = LoginContext.getUserId();
        return userId == null ? SYSTEM_USER_ID : userId;
    }
}
