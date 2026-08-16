package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 定时任务视图对象 */
@Data
@EqualsAndHashCode(callSuper = true)
public class JobVO extends BaseDTO {

    /** @XxlJob handler 名 */
    private String jobHandler;

    /** 任务显示名 */
    private String jobName;

    /** 调度类型：NONE/CRON/FIX_RATE */
    private String scheduleType;

    /** 调度配置 */
    private String scheduleConf;

    /** 任务参数 */
    private String executorParam;

    /** 过期策略 */
    private String misfireStrategy;

    /** 路由策略 */
    private String executorRouteStrategy;

    /** 阻塞策略 */
    private String executorBlockStrategy;

    /** 执行超时秒数 */
    private Integer executorTimeout;

    /** 失败重试次数 */
    private Integer executorFailRetryCount;

    /** 调度状态：0 暂停 1 运行 */
    private Integer triggerStatus;

    /** XXL-Job 侧 job ID（重构后 id 即为 XXL-Job admin 的 job ID，此字段保留兼容） */
    private Integer xxlJobId;

    /** 下次调度时间（时间戳） */
    private Long triggerNextTime;

    /** 上次调度时间（时间戳） */
    private Long triggerLastTime;
}
