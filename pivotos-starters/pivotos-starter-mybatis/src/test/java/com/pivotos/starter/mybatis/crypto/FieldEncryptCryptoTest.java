package com.pivotos.starter.mybatis.crypto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 字段加密单测
 */
class FieldEncryptCryptoTest {

    @BeforeAll
    static void initKey() {
        FieldEncryptCrypto.init("pivotos-aes-key!");
    }

    @Test
    void encryptDecryptRoundTrip() {
        String plain = "13800138000";
        String cipher = FieldEncryptCrypto.encrypt(plain);
        assertNotEquals(plain, cipher);
        assertEquals(plain, FieldEncryptCrypto.decrypt(cipher));
    }

    @Test
    void encryptIsDeterministic() {
        // ECB 确定性输出：同一明文密文相同，支持密文等值查询
        assertEquals(FieldEncryptCrypto.encrypt("abc"), FieldEncryptCrypto.encrypt("abc"));
    }

    @Test
    void illegalKeyLengthShouldFail() {
        assertThrows(IllegalStateException.class, () -> FieldEncryptCrypto.init("short"));
    }
}
