package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/** 定时任务新增/修改请求 */
@Data
public class JobSaveRequest {

    /** 任务 ID（修改时必填） */
    @Schema(description = "任务 ID（修改时必填）")
    private Long id;

    /** @XxlJob handler 名 */
    @NotBlank(message = "Handler 不能为空")
    @Size(max = 100, message = "Handler 不能超过 100 字")
    private String jobHandler;

    /** 任务显示名 */
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 100, message = "任务名称不能超过 100 字")
    private String jobName;

    /** 调度类型：NONE/CRON/FIX_RATE */
    @NotBlank(message = "调度类型不能为空")
    private String scheduleType;

    /** 调度配置（Cron 表达式或固定速率秒数） */
    @Schema(description = "调度配置（Cron 表达式或固定速率秒数）")
    private String scheduleConf;

    /** 任务参数 */
    @Size(max = 500, message = "任务参数不能超过 500 字")
    private String executorParam;

    /** 过期策略：DO_NOTHING/FIRE_ONCE_NOW */
    @Schema(description = "过期策略：DO_NOTHING/FIRE_ONCE_NOW")
    private String misfireStrategy;

    /** 路由策略 */
    @Schema(description = "路由策略")
    private String executorRouteStrategy;

    /** 阻塞策略 */
    @Schema(description = "阻塞策略")
    private String executorBlockStrategy;

    /** 执行超时秒数（0=不限） */
    @Schema(description = "执行超时秒数（0=不限）")
    private Integer executorTimeout;

    /** 失败重试次数（0=不重试） */
    @Schema(description = "失败重试次数（0=不重试）")
    private Integer executorFailRetryCount;
}
