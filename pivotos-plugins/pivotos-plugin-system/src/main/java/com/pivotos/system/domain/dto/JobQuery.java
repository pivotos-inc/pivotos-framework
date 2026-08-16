package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.swagger.v3.oas.annotations.media.Schema;
/** 定时任务分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class JobQuery extends PageQuery {

    /** 任务名（模糊） */
    @Schema(description = "任务名（模糊）")
    private String jobName;

    /** Handler 名（模糊） */
    @Schema(description = "Handler 名（模糊）")
    private String jobHandler;

    /** 调度状态（0 暂停 1 运行） */
    @Schema(description = "调度状态（0 暂停 1 运行）")
    private Integer triggerStatus;
}
