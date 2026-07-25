package com.pivotos.starter.mybatis.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 字段加密算法 holder：AES-128 ECB（确定性输出，支持密文等值查询）。
 * 密钥由 MybatisPlusAutoConfiguration 从 pivotos.mybatis.field-encrypt-key 注入。
 * 注意：TypeHandler 由 MyBatis 实例化而非 Spring，故用静态持有。
 */
public final class FieldEncryptCrypto {

    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private static volatile SecretKeySpec keySpec;

    private FieldEncryptCrypto() {
    }

    /**
     * 初始化密钥（自动配置阶段调用一次）
     *
     * @param key 16/24/32 字节密钥
     */
    public static void init(String key) {
        if (key == null || !(key.length() == 16 || key.length() == 24 || key.length() == 32)) {
            throw new IllegalStateException("pivotos.mybatis.field-encrypt-key 必须为 16/24/32 位字符串");
        }
        keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
    }

    /**
     * 是否已启用（未配置密钥时字段加密不生效，便于本地开发）
     */
    public static boolean isEnabled() {
        return keySpec != null;
    }

    /**
     * 加密，输出 Base64
     */
    public static String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("字段加密失败", e);
        }
    }

    /**
     * 解密
     */
    public static String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("字段解密失败", e);
        }
    }
}
