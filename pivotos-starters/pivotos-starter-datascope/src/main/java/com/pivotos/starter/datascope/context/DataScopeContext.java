package com.pivotos.starter.datascope.context;

import com.pivotos.starter.datascope.enums.DataScopeEnum;

import java.util.Set;

/**
 * 数据权限上下文静态门面（ScopedValue 实现，虚拟线程天然安全）。
 * 绑定入口仅 datascope 模块内使用，业务侧只读。
 *
 * @author PivotOS Team
 */
public final class DataScopeContext {

    /** 上下文键，绑定操作只允许 datascope Starter 使用 */
    public static final ScopedValue<DataScopeInfo> KEY = ScopedValue.newInstance();

    private DataScopeContext() {
    }

    /**
     * 当前请求的数据权限信息，未绑定返回 null
     */
    public static DataScopeInfo get() {
        return KEY.isBound() ? KEY.get() : null;
    }

    /**
     * 是否已绑定数据权限上下文
     */
    public static boolean isBound() {
        return KEY.isBound();
    }

    /**
     * 数据权限信息（纯 POJO，避免 Lombok 在 JDK 25 下的兼容性问题）
     */
    public static final class DataScopeInfo {
        /** 数据范围类型 */
        private final DataScopeEnum dataScope;
        /** 当前用户 ID */
        private final Long userId;
        /** 当前用户部门 ID */
        private final Long deptId;
        /** 可见部门 ID 集合（DEPT_AND_BELOW / CUSTOM 模式使用） */
        private final Set<Long> visibleDeptIds;
        /** 是否跳过数据权限过滤（超级管理员） */
        private final boolean skip;

        private DataScopeInfo(DataScopeEnum dataScope, Long userId, Long deptId,
                              Set<Long> visibleDeptIds, boolean skip) {
            this.dataScope = dataScope;
            this.userId = userId;
            this.deptId = deptId;
            this.visibleDeptIds = visibleDeptIds;
            this.skip = skip;
        }

        public DataScopeEnum getDataScope() {
            return dataScope;
        }

        public Long getUserId() {
            return userId;
        }

        public Long getDeptId() {
            return deptId;
        }

        public Set<Long> getVisibleDeptIds() {
            return visibleDeptIds;
        }

        public boolean isSkip() {
            return skip;
        }

        /**
         * 创建"跳过"模式的上下文（未登录 / 超级管理员无权限限制）
         */
        public static DataScopeInfo skip() {
            return new DataScopeInfo(DataScopeEnum.ALL, null, null, null, true);
        }

        /**
         * 创建"跳过"模式的上下文（超级管理员，带用户ID）
         */
        public static DataScopeInfo skip(Long userId) {
            return new DataScopeInfo(DataScopeEnum.ALL, userId, null, null, true);
        }

        /**
         * 创建自定义数据权限上下文
         */
        public static DataScopeInfo of(DataScopeEnum dataScope, Long userId, Long deptId, Set<Long> visibleDeptIds) {
            return new DataScopeInfo(dataScope, userId, deptId, visibleDeptIds, false);
        }
    }
}
