package com.pivotos.starter.web.encrypt;

import com.alibaba.fastjson2.JSON;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.web.annotation.ApiEncrypt;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Base64;

/**
 * 响应加密：对标注 @ApiEncrypt 的方法，用握手 AES 密钥加密响应 JSON，
 * 输出 Base64 文本（Content-Type: text/plain）。
 */
public class EncryptResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final ApiCryptoService cryptoService;
    private final EncryptSessionManager sessionManager;

    public EncryptResponseBodyAdvice(ApiCryptoService cryptoService, EncryptSessionManager sessionManager) {
        this.cryptoService = cryptoService;
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        ApiEncrypt annotation = returnType.getMethodAnnotation(ApiEncrypt.class);
        return annotation != null && annotation.encryptResponse();
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        byte[] aesKey = resolveAesKey(request);
        String json = JSON.toJSONString(body);
        byte[] encrypted = cryptoService.aesEncrypt(json.getBytes(StandardCharsets.UTF_8), aesKey);
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * AES 密钥优先取请求属性（请求解密阶段已缓存），否则从握手头重新解出
     */
    private byte[] resolveAesKey(ServerHttpRequest request) {
        String cached = request.getHeaders().getFirst(EncryptRequestBodyAdvice.ATTR_AES_KEY);
        if (cached != null) {
            return Base64.getDecoder().decode(cached);
        }
        String keyId = request.getHeaders().getFirst(EncryptKeyController.HEADER_KEY_ID);
        String encryptKey = request.getHeaders().getFirst(EncryptKeyController.HEADER_ENCRYPT_KEY);
        PrivateKey privateKey = keyId == null ? null : sessionManager.getPrivateKey(keyId);
        if (encryptKey == null || privateKey == null) {
            throw new ServiceException(GlobalErrorCode.CRYPTO_ERROR.getCode(), "缺少有效加密握手信息");
        }
        return cryptoService.rsaDecrypt(Base64.getDecoder().decode(encryptKey), privateKey);
    }
}
