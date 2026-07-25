package com.pivotos.starter.web.encrypt;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.web.annotation.ApiEncrypt;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.security.PrivateKey;
import java.util.Base64;

/**
 * 请求体解密：对标注 @ApiEncrypt 的方法，先 RSA 解出 AES 密钥，再 AES-GCM 解请求体。
 * 解出的 AES 密钥放入请求属性，供响应加密复用。
 */
public class EncryptRequestBodyAdvice implements RequestBodyAdvice {

    /** 请求属性键：当前请求的 AES 密钥 */
    public static final String ATTR_AES_KEY = "PIVOTOS_AES_KEY";

    private final ApiCryptoService cryptoService;
    private final EncryptSessionManager sessionManager;

    public EncryptRequestBodyAdvice(ApiCryptoService cryptoService, EncryptSessionManager sessionManager) {
        this.cryptoService = cryptoService;
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        ApiEncrypt annotation = methodParameter.getMethodAnnotation(ApiEncrypt.class);
        return annotation != null && annotation.decryptRequest()
                && methodParameter.hasParameterAnnotation(RequestBody.class);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        HttpHeaders headers = inputMessage.getHeaders();
        String keyId = headers.getFirst(EncryptKeyController.HEADER_KEY_ID);
        String encryptKey = headers.getFirst(EncryptKeyController.HEADER_ENCRYPT_KEY);
        if (keyId == null || encryptKey == null) {
            throw new ServiceException(GlobalErrorCode.CRYPTO_ERROR.getCode(), "缺少加密握手请求头");
        }
        PrivateKey privateKey = sessionManager.getPrivateKey(keyId);
        if (privateKey == null) {
            throw new ServiceException(GlobalErrorCode.CRYPTO_ERROR.getCode(), "密钥已过期，请重新握手");
        }
        byte[] aesKey = cryptoService.rsaDecrypt(Base64.getDecoder().decode(encryptKey), privateKey);
        byte[] cipherBody = Base64.getDecoder().decode(inputMessage.getBody().readAllBytes());
        byte[] plainBody = cryptoService.aesDecrypt(cipherBody, aesKey);

        // AES 密钥挂到请求属性，响应加密阶段复用
        headers.set(ATTR_AES_KEY, Base64.getEncoder().encodeToString(aesKey));

        byte[] finalPlain = plainBody;
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(finalPlain);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                  Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }
}
