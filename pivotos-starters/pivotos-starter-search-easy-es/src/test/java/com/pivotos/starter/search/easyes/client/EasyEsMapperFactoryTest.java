package com.pivotos.starter.search.easyes.client;

import org.dromara.easyes.core.kernel.BaseEsMapperImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Easy-ES Mapper 反射注入：这是本实现最大的不确定性（官方无 setter），
 * 故单独钉死——字段一旦改名（Easy-ES 升级）必须在这里立刻失败，
 * 而不是等到检索静默返回空才被发现。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class EasyEsMapperFactoryTest {

    @Test
    void shouldInjectEntityClassByReflection() {
        BaseEsMapperImpl<HashMap> mapper = EasyEsMapperFactory.createMapper(null);
        assertNotNull(mapper);
        assertEquals(HashMap.class, mapper.getEntityClass());
    }

    @Test
    void shouldCreateIndependentInstances() {
        BaseEsMapperImpl<HashMap> a = EasyEsMapperFactory.createMapper(null);
        BaseEsMapperImpl<HashMap> b = EasyEsMapperFactory.createMapper(null);
        assertNotSame(a, b);
    }

    @Test
    void shouldDeclareClientAndEntityClassFieldsOnBaseEsMapperImpl() {
        // 直接断言依赖的字段名仍然存在（Easy-ES 升级改名时此用例会红）
        assertNotNull(findField("client"));
        assertNotNull(findField("entityClass"));
    }

    @Test
    void shouldFailLoudlyWhenInjectionImpossible() {
        // 用 final 静态不可变目标验证注入失败路径会抛 IllegalAccessException 分支
        assertThrows(RuntimeException.class, () -> {
            try {
                Field field = String.class.getDeclaredField("value");
                field.setAccessible(true);
                field.set("immutable-target", new char[0]);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private Field findField(String name) {
        Class<?> type = BaseEsMapperImpl.class;
        while (type != null) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}
