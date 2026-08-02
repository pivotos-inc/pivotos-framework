package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 定时任务执行记录（平台共享表，已登记租户忽略） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_job_log")
public class SysJobLog extends BaseDO {

    /** XXL-Job handler 名 */
    private String jobHandler;

    /** 结果（0成功 1失败） */
    private Integer status;

    /** 异常信息（失败时） */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long duration;

    /** 执行时间 */
    private LocalDateTime executeTime;
}
