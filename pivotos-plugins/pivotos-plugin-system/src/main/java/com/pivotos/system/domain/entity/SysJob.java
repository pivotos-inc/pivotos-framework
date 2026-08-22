package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 定时任务管理实体（平台共享表，已登记租户忽略） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_job")
public class SysJob extends BaseDO {

    /** @XxlJob handler 名 */
    private String jobHandler;

    /** 任务显示名 */
    private String jobName;

    /** 调度类型：NONE/CRON/FIX_RATE */
    private String scheduleType;

    /** 调度配置（Cron 表达式或固定速率秒数） */
    private String scheduleConf;

    /** 任务参数 */
    private String executorParam;

    /** 过期策略：DO_NOTHING/FIRE_ONCE_NOW */
    private String misfireStrategy;

    /** 路由策略：FIRST/LAST/ROUND/... */
    private String executorRouteStrategy;

    /** 阻塞策略：SERIAL_EXECUTION/DISCARD_LATER/COVER_EARLY */
    private String executorBlockStrategy;

    /** 执行超时秒数（0=不限） */
    private Integer executorTimeout;

    /** 失败重试次数（0=不重试） */
    private Integer executorFailRetryCount;

    /** 调度状态：0 暂停 1 运行 */
    private Integer triggerStatus;

    /** XXL-Job 侧 job ID（同步后回写） */
    private Integer xxlJobId;
}
