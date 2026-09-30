package com.pivotos.starter.search.api.function;

import java.io.Serializable;

/**
 * 搜索字段引用函数（可序列化，供 Lambda 字段名解析使用）。
 * <p>与 MyBatis-Plus 的 SFunction 同思路：必须是 Serializable，否则 JVM 不会生成
 * writeReplace 方法，{@link com.pivotos.starter.search.api.core.LambdaFieldResolver} 拿不到 SerializedLambda。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@FunctionalInterface
public interface SearchSFunction<T, R> extends Serializable {

    /**
     * 取字段值（仅用于签名约束，运行期不执行——实际取的是序列化后的方法名）
     */
    R apply(T source);
}
