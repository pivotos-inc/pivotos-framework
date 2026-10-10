package com.pivotos.starter.cloud.local.rpc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Jackson 2 兜底实例。
 *
 * <p>为什么用 Jackson 2（{@code com.fasterxml}）而不是项目主用的 Jackson 3（{@code tools.jackson}）：
 * 泛型返回值的 {@code TypeReference} / {@code JavaType} 反序列化 API 在 Jackson 2 上最稳，
 * 且两者包名不同可共存（BOM 已钉 2.21.4，与 datainspect 模块同款做法）。
 * 容器里有 Jackson 2 的 {@code ObjectMapper} bean 时优先用容器的，没有才用这里的兜底。
 */
public final class JsonSupport {

    private static final ObjectMapper FALLBACK = create();

    private JsonSupport() {
    }

    public static ObjectMapper fallback() {
        return FALLBACK;
    }

    private static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
