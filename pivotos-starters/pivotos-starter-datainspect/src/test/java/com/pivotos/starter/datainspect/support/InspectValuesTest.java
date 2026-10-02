package com.pivotos.starter.datainspect.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结果值归一：截断是「防拖库」红线的一部分，不是体验优化 */
class InspectValuesTest {

    @Test
    void 空值与二进制安全处理() {
        assertNull(InspectValues.normalize(null, 10));
        assertEquals("<binary:4 bytes>", InspectValues.normalize(new byte[]{1, 2, 3, 4}, 10));
    }

    @Test
    void 超长文本截断并打标记() {
        String text = "0123456789";
        assertEquals("01234" + InspectValues.TRUNCATED_SUFFIX, InspectValues.normalize(text, 5));
        assertTrue(InspectValues.isTruncated(InspectValues.normalize(text, 5)));
    }

    @Test
    void 未超长原样返回且不打标记() {
        assertEquals("abc", InspectValues.normalize("abc", 5));
        assertFalse(InspectValues.isTruncated("abc"));
    }

    @Test
    void 非正数上限回落到默认2048() {
        String text = "x".repeat(3000);
        Object value = InspectValues.normalize(text, 0);
        assertTrue(((String) value).endsWith(InspectValues.TRUNCATED_SUFFIX));
        assertEquals(2048 + InspectValues.TRUNCATED_SUFFIX.length(), ((String) value).length());
    }

    @Test
    void 数值与布尔原样透出() {
        assertEquals(1, InspectValues.normalize(1, 10));
        assertEquals(true, InspectValues.normalize(true, 10));
    }
}
