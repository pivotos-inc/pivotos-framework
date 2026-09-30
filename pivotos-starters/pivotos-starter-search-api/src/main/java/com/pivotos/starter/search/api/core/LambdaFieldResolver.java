package com.pivotos.starter.search.api.core;

import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.function.SearchSFunction;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lambda 字段名解析器：{@code SysOperLog::getTitle} → {@code "title"}。
 * <p>自研实现（不依赖 MyBatis-Plus），保持本 Starter 零耦合。实现走 JDK 标准的
 * {@link SerializedLambda} 通道：Lambda 实现类因继承 Serializable 而带 writeReplace 方法，
 * 反射调用它即可拿到编译期固定的实现方法名。
 *
 * <p><b>为什么缓存 key 用 Lambda 的 Class 而不是实例</b>：同一调用点的 Lambda 由 JVM 复用同一
 * 个实现类，且 implMethodName 与捕获状态无关，按类缓存安全。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class LambdaFieldResolver {

    private static final Map<Class<?>, String> CACHE = new ConcurrentHashMap<>();

    private LambdaFieldResolver() {
    }

    /**
     * 解析 Lambda 引用的字段名（camelCase）。
     *
     * @throws SearchException 8100：不是 getter 引用 / 无 writeReplace / 反射被拒
     */
    public static <T> String resolve(SearchSFunction<T, ?> function) {
        if (function == null) {
            throw new SearchException(SearchErrorCode.LAMBDA_FIELD_UNRESOLVED, "Lambda 表达式为 null");
        }
        // 缓存 key 用 Lambda 实现类，但反射必须拿实例调用（writeReplace 是实例方法，传 null 会 NPE）
        return CACHE.computeIfAbsent(function.getClass(), k -> doResolve(function));
    }

    private static String doResolve(SearchSFunction<?, ?> function) {
        SerializedLambda lambda = extract(function);
        String methodName = lambda.getImplMethodName();
        String field = toFieldName(methodName);
        if (field == null) {
            throw new SearchException(SearchErrorCode.LAMBDA_FIELD_UNRESOLVED,
                    "方法名 " + methodName + " 不是 getter/is 引用");
        }
        return field;
    }

    private static SerializedLambda extract(SearchSFunction<?, ?> function) {
        try {
            Method writeReplace = function.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object serialized = writeReplace.invoke(function);
            if (!(serialized instanceof SerializedLambda)) {
                throw new SearchException(SearchErrorCode.LAMBDA_FIELD_UNRESOLVED,
                        "writeReplace 未返回 SerializedLambda");
            }
            return (SerializedLambda) serialized;
        } catch (SearchException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new SearchException(SearchErrorCode.LAMBDA_FIELD_UNRESOLVED,
                    "反射 writeReplace 失败（" + e.getClass().getSimpleName() + "）", e);
        }
    }

    /**
     * getter/isXxx → 字段名（首字母小写）。非 getter 命名返回 null 交由调用方报错。
     */
    private static String toFieldName(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return null;
        }
        String raw;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            raw = methodName.substring(3);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            raw = methodName.substring(2);
        } else {
            return null;
        }
        return Character.toLowerCase(raw.charAt(0)) + raw.substring(1);
    }
}
