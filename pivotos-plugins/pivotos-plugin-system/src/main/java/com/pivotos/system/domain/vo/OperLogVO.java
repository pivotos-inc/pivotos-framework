package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 操作日志视图对象 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperLogVO extends BaseDTO {

    /** 功能模块 */
    private String module;

    /** 操作类型 */
    private String operType;

    /** 操作人用户名 */
    private String operName;

    /** 操作人 ID */
    private Long operUserId;

    /** 目标方法 */
    private String method;

    /** HTTP 方法 */
    private String requestMethod;

    /** 请求 URL */
    private String requestUrl;

    /** 入参摘要（已脱敏） */
    private String requestParams;

    /** 结果（0成功 1失败） */
    private Integer status;

    /** 异常信息 */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long duration;

    /** 操作时间 */
    private LocalDateTime operTime;
}
