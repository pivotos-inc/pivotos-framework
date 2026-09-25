package com.pivotos.ai.tool;

import com.pivotos.ai.enums.ToolType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AI 工具元数据声明（S98 A2）：与 @Tool 同标在业务方法上。
 *
 * <p>启动同步器反射扫描时读取，落入 ai_tool 元数据：
 * <ul>
 *   <li>type：read（默认）/ write——write 类工具纳入二次确认协议管辖；</li>
 *   <li>confirmRequired：write 类是否强制 confirm=true 预检（默认 true）。</li>
 * </ul>
 * 未标注本注解的 @Tool 方法按「只读 + 无需确认」默认口径注册。
 *
 * <p><b>二次确认协议实现约束（S98 实测定型）</b>：confirmRequired=true 的工具，
 * 其 @Tool 方法必须显式声明 {@code boolean confirm} 参数并在描述中说明
 * 「先预检后确认」语义——Spring AI 按方法签名生成 JSON Schema 并严格校验入参，
 * 守卫层协议约定的 confirm 键若不在签名内会被 Schema 校验拒绝。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiToolMeta {

    /** 工具类型（默认只读） */
    ToolType type() default ToolType.READ;

    /** 写操作是否需二次确认（confirm=true 才真实执行，默认需要） */
    boolean confirmRequired() default true;
}
