package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 操作日志分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperLogQuery extends PageQuery {

    /** 功能模块（模糊） */
    private String module;

    /** 操作类型 */
    private String operType;

    /** 操作人用户名（模糊） */
    private String operName;

    /** 结果（0成功 1失败） */
    private Integer status;

    /** 操作时间起 */
    private LocalDateTime beginTime;

    /** 操作时间止 */
    private LocalDateTime endTime;
}
