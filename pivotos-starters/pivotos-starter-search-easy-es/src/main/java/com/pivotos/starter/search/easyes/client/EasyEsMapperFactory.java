package com.pivotos.starter.search.easyes.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;
import org.dromara.easyes.core.kernel.BaseEsMapperImpl;

import java.lang.reflect.Field;
import java.util.HashMap;

/**
 * Easy-ES {@code BaseEsMapperImpl} 实例工厂。
 * <p><b>为什么需要反射</b>：{@code BaseEsMapperImpl} 只有无参构造器，{@code client} 与
 * {@code entityClass} 两个私有字段没有公开 setter——官方由 easy-es-spring 模块反射注入，
 * 而该模块的 Spring Boot 基线是 2.7.16（Spring 5.3.30），其 ClassPathMapperScanner 在
 * Spring 6+ 已迁包，本项目 Spring Boot 4.1 下不可用，故自行注入。
 *
 * <p><b>为什么实体载体是 HashMap</b>：Easy-ES 解析实体元信息时会沿 {@code getSuperclass()} 上溯，
 * 接口的 {@code getSuperclass()} 为 null，传 {@code Map.class} 会抛
 * {@code EasyEsException: Class must not be null}（已实测）。
 *
 * <p>字段找不到（Easy-ES 版本升级改了字段名）时<b>直接抛带业务码的异常</b>，不做静默降级
 * ——静默降级会让「条件构建看起来正常但检索永远查不到数据」。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EasyEsMapperFactory {

    private EasyEsMapperFactory() {
    }

    /**
     * 创建绑定了客户端的 Mapper（实体载体固定 HashMap：Provider 层只处理扁平文档）
     */
    public static BaseEsMapperImpl<HashMap> createMapper(ElasticsearchClient client) {
        BaseEsMapperImpl<HashMap> mapper = new BaseEsMapperImpl<>();
        setPrivateField(mapper, "client", client);
        setPrivateField(mapper, "entityClass", HashMap.class);
        return mapper;
    }

    private static void setPrivateField(Object target, String fieldName, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new SearchException(SearchErrorCode.PROVIDER_NOT_FOUND,
                        "注入 easy-es 字段 " + fieldName + " 失败（" + e.getClass().getSimpleName() + "）", e);
            }
        }
        throw new SearchException(SearchErrorCode.PROVIDER_NOT_FOUND,
                "easy-es 版本不兼容：BaseEsMapperImpl 缺少字段 " + fieldName);
    }
}
