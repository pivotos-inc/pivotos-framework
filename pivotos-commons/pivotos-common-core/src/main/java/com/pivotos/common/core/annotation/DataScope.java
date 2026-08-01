package com.pivotos.common.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限注解 —— 标注需要行级数据过滤的 Mapper 方法。
 * <p>
 * 配合 {@code pivotos-starter-datascope} 模块使用：
 * 当该 Starter 存在时，MyBatis 拦截器自动根据当前用户角色数据范围注入 WHERE 条件；
 * 当该 Starter 不存在时，此注解仅作为标记存在，不影响查询行为。
 * <p>
 * 装配验证：增删 {@code pivotos-starter-datascope} 依赖无需任何代码改动。
 * <p>
 * 与 TenantLineInnerInterceptor 共存：tenant 列过滤（executor 阶段）先于
 * 数据权限行过滤（StatementHandler 阶段），两者互不冲突。
 *
 * @author PivotOS Team
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {

    /**
     * 部门列名（默认 {@code dept_id}）
     */
    String deptColumn() default "dept_id";

    /**
     * 用户列名（默认 {@code create_by}）
     */
    String userColumn() default "create_by";
}
