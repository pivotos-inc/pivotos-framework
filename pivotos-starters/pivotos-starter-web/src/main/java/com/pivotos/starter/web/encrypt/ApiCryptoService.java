package com.pivotos.starter.web.encrypt;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 加解密服务：RSA-2048（密钥交换）+ AES-128-GCM（报文加密）。
 * GCM 密文结构：12 字节 IV 前缀 + 密文。
 */
public class ApiCryptoService {

    private static final String RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * 生成 RSA-2048 密钥对
     */
    public KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new ServiceException(GlobalErrorCode.CRYPTO_ERROR);
        }
    }

    /**
     * Base64 编码公钥（X.509）
     */
    public String encodePublicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /**
     * RSA 私钥解密（解出客户端的 AES 密钥）
     */
    public byte[] rsaDecrypt(byte[] data, PrivateKey privateKey) {
        try {
            Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new ServiceException(GlobalErrorCode.CRYPTO_ERROR);
        }
    }

    /**
     * AES-GCM 加密，输出 = IV + 密文
     */
    public byte[] aesEncrypt(byte[] plain, byte[] aesKey) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] result = new byte[GCM_IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(encrypted, 0, result, GCM_IV_LENGTH, encrypted.length);
            return result;
        } catch (Exception e) {
            throw new ServiceException(GlobalErrorCode.CRYPTO_ERROR);
        }
    }

    /**
     * AES-GCM 解密，输入 = IV + 密文
     */
    public byte[] aesDecrypt(byte[] cipherWithIv, byte[] aesKey) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(cipherWithIv, 0, iv, 0, GCM_IV_LENGTH);
            byte[] encrypted = new byte[cipherWithIv.length - GCM_IV_LENGTH];
            System.arraycopy(cipherWithIv, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new ServiceException(GlobalErrorCode.CRYPTO_ERROR);
        }
    }
}
