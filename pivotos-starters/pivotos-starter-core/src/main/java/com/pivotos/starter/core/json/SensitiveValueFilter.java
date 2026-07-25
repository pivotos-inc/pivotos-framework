package com.pivotos.starter.core.json;

import com.alibaba.fastjson2.filter.ValueFilter;
import com.pivotos.common.core.sensitive.Sensitive;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * fastjson2 脱敏过滤器：序列化时检查字段上的 @Sensitive 注解并脱敏。
 * 字段反射结果带缓存，避免每次序列化重复扫描。
 */
public class SensitiveValueFilter implements ValueFilter {

    private final Map<String, Optional<Field>> fieldCache = new ConcurrentHashMap<>();

    @Override
    public Object apply(Object object, String name, Object value) {
        if (!(value instanceof String str) || object == null) {
            return value;
        }
        Optional<Field> field = fieldCache.computeIfAbsent(
                object.getClass().getName() + "#" + name,
                key -> findField(object.getClass(), name));
        if (field.isEmpty()) {
            return value;
        }
        Sensitive sensitive = field.get().getAnnotation(Sensitive.class);
        return sensitive == null ? value : SensitiveMasker.mask(str, sensitive.type());
    }

    private Optional<Field> findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return Optional.of(current.getDeclaredField(name));
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return Optional.empty();
    }
}
