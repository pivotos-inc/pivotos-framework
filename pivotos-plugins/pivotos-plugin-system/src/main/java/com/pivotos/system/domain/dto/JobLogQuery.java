package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 定时任务执行记录分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class JobLogQuery extends PageQuery {

    /** handler 名（模糊） */
    private String jobHandler;

    /** 结果（0成功 1失败） */
    private Integer status;

    /** 执行时间起 */
    private LocalDateTime beginTime;

    /** 执行时间止 */
    private LocalDateTime endTime;
}
