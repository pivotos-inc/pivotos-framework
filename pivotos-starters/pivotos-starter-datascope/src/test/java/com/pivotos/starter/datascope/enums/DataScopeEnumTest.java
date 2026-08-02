package com.pivotos.starter.datascope.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DataScopeEnum 枚举转换单元测试。
 */
@DisplayName("DataScopeEnum 枚举转换")
class DataScopeEnumTest {

    @Nested
    @DisplayName("fromCode 正常映射")
    class NormalMapping {

        @Test
        @DisplayName("code=1 → ALL")
        void code1ToAll() {
            assertEquals(DataScopeEnum.ALL, DataScopeEnum.fromCode(1));
        }

        @Test
        @DisplayName("code=2 → DEPT")
        void code2ToDept() {
            assertEquals(DataScopeEnum.DEPT, DataScopeEnum.fromCode(2));
        }

        @Test
        @DisplayName("code=3 → DEPT_AND_BELOW")
        void code3ToDeptAndBelow() {
            assertEquals(DataScopeEnum.DEPT_AND_BELOW, DataScopeEnum.fromCode(3));
        }

        @Test
        @DisplayName("code=4 → SELF")
        void code4ToSelf() {
            assertEquals(DataScopeEnum.SELF, DataScopeEnum.fromCode(4));
        }

        @Test
        @DisplayName("code=5 → CUSTOM")
        void code5ToCustom() {
            assertEquals(DataScopeEnum.CUSTOM, DataScopeEnum.fromCode(5));
        }
    }

    @Nested
    @DisplayName("fromCode 边界/异常")
    class EdgeCases {

        @Test
        @DisplayName("null → ALL（默认回退）")
        void nullReturnsAll() {
            assertEquals(DataScopeEnum.ALL, DataScopeEnum.fromCode(null));
        }

        @Test
        @DisplayName("无效 code → ALL（安全回退）")
        void invalidCodeReturnsAll() {
            assertEquals(DataScopeEnum.ALL, DataScopeEnum.fromCode(999));
            assertEquals(DataScopeEnum.ALL, DataScopeEnum.fromCode(0));
            assertEquals(DataScopeEnum.ALL, DataScopeEnum.fromCode(-1));
        }
    }

    @Nested
    @DisplayName("getCode / getDesc")
    class CodeAndDesc {

        @Test
        @DisplayName("getCode 返回正确的数字")
        void getCode() {
            assertEquals(1, DataScopeEnum.ALL.getCode());
            assertEquals(2, DataScopeEnum.DEPT.getCode());
            assertEquals(3, DataScopeEnum.DEPT_AND_BELOW.getCode());
            assertEquals(4, DataScopeEnum.SELF.getCode());
            assertEquals(5, DataScopeEnum.CUSTOM.getCode());
        }

        @Test
        @DisplayName("getDesc 返回正确的中文描述")
        void getDesc() {
            assertEquals("全部数据权限", DataScopeEnum.ALL.getDesc());
            assertEquals("仅本人", DataScopeEnum.SELF.getDesc());
            assertEquals("自定义部门", DataScopeEnum.CUSTOM.getDesc());
        }
    }
}
