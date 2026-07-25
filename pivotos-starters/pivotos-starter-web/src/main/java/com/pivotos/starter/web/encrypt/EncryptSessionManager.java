package com.pivotos.starter.web.encrypt;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RSA 密钥对会话管理：内存缓存 + 惰性过期清理。
 * P0 单体阶段够用；集群化后需迁移到 Redis（记技术债）。
 */
public class EncryptSessionManager {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(KeyPair keyPair, long expireAt) {
    }

    /**
     * 签发一对 RSA 密钥，返回 keyId
     */
    public String create(KeyPair keyPair, long expireMillis) {
        String keyId = UUID.randomUUID().toString().replace("-", "");
        store.put(keyId, new Entry(keyPair, System.currentTimeMillis() + expireMillis));
        return keyId;
    }

    /**
     * 取私钥，不存在或已过期返回 null
     */
    public PrivateKey getPrivateKey(String keyId) {
        Entry entry = store.get(keyId);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt()) {
            store.remove(keyId);
            return null;
        }
        return entry.keyPair().getPrivate();
    }
}
