package com.pivotos.${pluginName}.api.constant;

import com.pivotos.common.core.enums.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * ${displayName}插件错误码（${errorCodeBase?c}xx 段，由 AI Coding 错误码段分配器自动分配，与既有段不冲突）。
 * <p>
 * 骨架占位：按业务需要增删常量，段号不得改动（跨插件段冲突检查依赖此约定）。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Getter
@AllArgsConstructor
public enum ${className}ErrorCode implements ErrorCode {

    /** 通用业务错误占位 */
    ${pluginName?upper_case}_BIZ_ERROR(${(errorCodeBase + 1)?c}, "业务处理失败"),

    /** 资源不存在占位 */
    ${pluginName?upper_case}_NOT_FOUND(${(errorCodeBase + 2)?c}, "资源不存在"),

    ;

    private final int code;
    private final String msg;
}
