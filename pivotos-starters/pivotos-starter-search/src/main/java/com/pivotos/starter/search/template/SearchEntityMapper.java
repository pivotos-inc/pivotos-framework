package com.pivotos.starter.search.template;

import com.alibaba.fastjson2.JSON;
import com.pivotos.starter.search.api.core.SearchIndexNameResolver;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实体 ↔ 扁平文档映射（统一走 fastjson2，禁 AutoType）。
 * <p>映射放在门面层而不下沉到 Provider，是为了让各实现只处理最薄的文档语义
 * ——新增一个搜索引擎实现时不必重做实体映射。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class SearchEntityMapper {

    private SearchEntityMapper() {
    }

    /**
     * 实体 → 扁平 Map
     */
    public static Map<String, Object> toMap(Object entity) {
        if (entity == null) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID, "待索引实体不能为空");
        }
        Map<String, Object> map = JSON.parseObject(JSON.toJSONString(entity), Map.class);
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    /**
     * 扁平 Map → 实体
     */
    public static <T> T fromMap(Map<String, Object> source, Class<T> type) {
        if (source == null || type == null) {
            return null;
        }
        return JSON.parseObject(JSON.toJSONString(source), type);
    }

    /**
     * 取实体主键作为文档 ID：优先 {@code getId()}，其次 {@code id} 字段。
     * <p>索引必须有稳定主键，否则同 id 覆盖写无从谈起（批量重索引会无限堆积）。
     */
    public static String resolveDocId(Object entity) {
        if (entity == null) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID, "待索引实体不能为空");
        }
        try {
            Method getter = entity.getClass().getMethod("getId");
            Object id = getter.invoke(entity);
            if (id != null) {
                return id.toString();
            }
        } catch (NoSuchMethodException ignored) {
            // 落到字段反射
        } catch (ReflectiveOperationException e) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID, "读取实体主键失败", e);
        }
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            Object id = field.get(entity);
            if (id != null) {
                return id.toString();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID,
                    "实体 " + entity.getClass().getSimpleName() + " 既无 getId() 也无 id 字段，无法确定文档 ID", e);
        }
        throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID,
                "实体 " + entity.getClass().getSimpleName() + " 主键为空，无法索引");
    }

    /**
     * 解析实体对应的索引名（含全局前缀）
     */
    public static String resolveIndexName(Class<?> type, String prefix) {
        return SearchIndexNameResolver.applyPrefix(SearchIndexNameResolver.resolve(type), prefix);
    }
}
