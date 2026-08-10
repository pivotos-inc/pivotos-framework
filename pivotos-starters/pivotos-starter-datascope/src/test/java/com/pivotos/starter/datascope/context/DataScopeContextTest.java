package com.pivotos.starter.datascope.context;

import com.pivotos.starter.datascope.context.DataScopeContext.DataScopeInfo;
import com.pivotos.starter.datascope.enums.DataScopeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataScopeInfo 数据权限上下文 POJO 单元测试。
 */
@DisplayName("DataScopeInfo 上下文对象")
class DataScopeContextTest {

    @Nested
    @DisplayName("skip() 工厂方法")
    class SkipFactory {

        @Test
        @DisplayName("skip() 无参 → isSkip=true, dataScope=ALL")
        void skipNoArg() {
            DataScopeInfo info = DataScopeInfo.skip();
            assertTrue(info.isSkip());
            assertEquals(DataScopeEnum.ALL, info.getDataScope());
            assertNull(info.getUserId());
            assertNull(info.getDeptId());
        }

        @Test
        @DisplayName("skip(userId) → isSkip=true, 保留 userId")
        void skipWithUserId() {
            DataScopeInfo info = DataScopeInfo.skip(99L);
            assertTrue(info.isSkip());
            assertEquals(DataScopeEnum.ALL, info.getDataScope());
            assertEquals(99L, info.getUserId());
            assertNull(info.getDeptId());
        }
    }

    @Nested
    @DisplayName("of() 工厂方法")
    class OfFactory {

        @Test
        @DisplayName("of(SELF, userId, deptId, null) → isSkip=false")
        void selfMode() {
            DataScopeInfo info = DataScopeInfo.of(DataScopeEnum.SELF, 1L, 10L, null);
            assertFalse(info.isSkip());
            assertEquals(DataScopeEnum.SELF, info.getDataScope());
            assertEquals(1L, info.getUserId());
            assertEquals(10L, info.getDeptId());
            assertNull(info.getVisibleDeptIds());
        }

        @Test
        @DisplayName("of(DEPT_AND_BELOW, ...) → 包含可见部门集合")
        void deptAndBelowMode() {
            Set<Long> ids = Set.of(10L, 11L, 12L);
            DataScopeInfo info = DataScopeInfo.of(DataScopeEnum.DEPT_AND_BELOW, 1L, 10L, ids);
            assertFalse(info.isSkip());
            assertEquals(DataScopeEnum.DEPT_AND_BELOW, info.getDataScope());
            assertEquals(ids, info.getVisibleDeptIds());
        }

        @Test
        @DisplayName("of(CUSTOM, ...) → 包含自定义部门集合")
        void customMode() {
            Set<Long> ids = Set.of(100L, 200L);
            DataScopeInfo info = DataScopeInfo.of(DataScopeEnum.CUSTOM, 2L, 5L, ids);
            assertEquals(DataScopeEnum.CUSTOM, info.getDataScope());
            assertEquals(2L, info.getUserId());
            assertEquals(5L, info.getDeptId());
            assertEquals(ids, info.getVisibleDeptIds());
        }
    }
}
