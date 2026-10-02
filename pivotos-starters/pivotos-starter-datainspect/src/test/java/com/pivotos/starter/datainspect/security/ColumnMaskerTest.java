package com.pivotos.starter.datainspect.security;

import com.pivotos.common.core.sensitive.SensitiveType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 敏感列脱敏：动态结果集（Map）无法用 @Sensitive 注解，只能按列名规则脱敏 */
class ColumnMaskerTest {

    private final ColumnMasker masker = new ColumnMasker(null);

    @Test
    void 凭据类列名命中并全星() {
        assertTrue(masker.isSensitive("password"));
        assertTrue(masker.isSensitive("user_token"));
        assertTrue(masker.isSensitive("api_key"));
        assertTrue(masker.isSensitive("privateKey"));
        assertEquals(SensitiveType.ALL, masker.typeOf("password"));
        assertEquals("********", masker.mask("password", "12345678"));
    }

    @Test
    void 手机号邮箱部分掩码保持可读性() {
        assertEquals(SensitiveType.MOBILE, masker.typeOf("mobile"));
        assertEquals(SensitiveType.EMAIL, masker.typeOf("email"));
        assertEquals("138****1234", masker.mask("mobile", "13812341234"));
        assertEquals("a***@pivotos.com", masker.mask("email", "alex@pivotos.com"));
    }

    @Test
    void 普通列名不脱敏() {
        assertFalse(masker.isSensitive("user_name"));
        assertFalse(masker.isSensitive("create_time"));
        assertEquals("张三", masker.mask("user_name", "张三"));
    }

    @Test
    void 空值与非字符串安全处理() {
        assertNull(masker.mask("password", null));
        // 未命中规则的列原样返回（不转换类型）
        assertEquals(1, masker.mask("id", 1));
        // 命中规则的列按字符串掩码
        assertEquals("****", masker.mask("token", 1234));
    }

    @Test
    void 键名命中即脱敏_Redis的key本身就是凭证() {
        // 列名固定为 value（不命中），但 key 名含 token —— 只看列名会整片漏脱敏
        assertFalse(masker.isSensitive("value"));
        assertTrue(masker.isSensitive("value", "Authorization:sys-user:token:abc"));
        assertEquals("********", masker.mask("value", "abcdefgh", "Authorization:sys-user:token:abc"));
        // key 不敏感时列名也不敏感 → 原样返回
        assertEquals("abcdefgh", masker.mask("value", "abcdefgh", "sys:config:site"));
    }

    @Test
    void 键名与列名都命中时按键名定类型() {
        // key 含 mobile → 走 MOBILE 部分掩码（比全星更可读）
        assertEquals("138****1234", masker.mask("value", "13812341234", "sys:mobile:bind:1"));
    }
}
