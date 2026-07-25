package com.pivotos.starter.web.encrypt;

import com.pivotos.common.core.result.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.util.Map;

/**
 * 加密握手接口：签发 RSA 公钥。
 * 流程：客户端拿公钥 → 本地生成 AES 密钥 → RSA 加密后放入请求头 X-Encrypt-Key，
 * 并携带 X-Encrypt-Key-Id，之后请求/响应均用该 AES 密钥（GCM）加解密。
 */
@RestController
@RequestMapping("/crypto")
public class EncryptKeyController {

    public static final String HEADER_KEY_ID = "X-Encrypt-Key-Id";
    public static final String HEADER_ENCRYPT_KEY = "X-Encrypt-Key";

    private final ApiCryptoService cryptoService;
    private final EncryptSessionManager sessionManager;
    private final long expireMillis;

    public EncryptKeyController(ApiCryptoService cryptoService, EncryptSessionManager sessionManager, long expireMillis) {
        this.cryptoService = cryptoService;
        this.sessionManager = sessionManager;
        this.expireMillis = expireMillis;
    }

    /**
     * 获取 RSA 公钥
     */
    @GetMapping("/public-key")
    public R<Map<String, String>> publicKey() {
        KeyPair keyPair = cryptoService.generateRsaKeyPair();
        String keyId = sessionManager.create(keyPair, expireMillis);
        return R.ok(Map.of(
                "keyId", keyId,
                "publicKey", cryptoService.encodePublicKey(keyPair)));
    }
}
