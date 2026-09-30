package com.pivotos.starter.search.api.core;

import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.fixture.SearchTestEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lambda 字段名解析：这是「Lambda 门面 → 确定性条件树」的唯一转换点，
 * 解析错一个字母，三种实现的检索语义会一起错，故单独钉死。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class LambdaFieldResolverTest {

    @Test
    void shouldResolveGetter() {
        assertEquals("title", LambdaFieldResolver.resolve(SearchTestEntity::getTitle));
    }

    @Test
    void shouldResolveIsGetterForBoolean() {
        assertEquals("enabled", LambdaFieldResolver.resolve(SearchTestEntity::getEnabled));
    }

    @Test
    void shouldResolveNumericAndTimeFields() {
        assertEquals("id", LambdaFieldResolver.resolve(SearchTestEntity::getId));
        assertEquals("status", LambdaFieldResolver.resolve(SearchTestEntity::getStatus));
        assertEquals("operTime", LambdaFieldResolver.resolve(SearchTestEntity::getOperTime));
    }

    @Test
    void shouldBeStableAcrossCallsWithCache() {
        // 缓存按 Lambda 实现类做 key；同一调用点重复解析必须完全一致（否则条件树会漂移）
        for (int i = 0; i < 3; i++) {
            assertEquals("title", LambdaFieldResolver.resolve(SearchTestEntity::getTitle));
        }
    }

    @Test
    void shouldRejectNullFunction() {
        SearchException e = assertThrows(SearchException.class, () -> LambdaFieldResolver.resolve(null));
        assertEquals(SearchErrorCode.LAMBDA_FIELD_UNRESOLVED.getCode(), e.getCode());
    }

    @Test
    void shouldRejectNonGetterMethodReference() {
        // 用非 getter 的静态方法引用（Integer::valueOf 的实现方法名是 valueOf）
        SearchException e = assertThrows(SearchException.class,
                () -> LambdaFieldResolver.resolve((com.pivotos.starter.search.api.function.SearchSFunction<String, Integer>)
                        Integer::valueOf));
        assertEquals(SearchErrorCode.LAMBDA_FIELD_UNRESOLVED.getCode(), e.getCode());
        assertNotNull(e.getMessage());
    }
}
