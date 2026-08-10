package com.pivotos.starter.job.api;

/**
 * 任务执行记录持久化 SPI。
 * <p>
 * 由业务插件（当前为 pivotos-plugin-system 落 sys_job_log 表）提供实现；
 * starter 只定义接口，无实现时任务照常执行、仅不记录（增删实现 0 改动）。
 *
 * @author PivotOS
 * @since 2.2.0
 */
public interface JobExecutionRecorder {

    /**
     * 记录一次任务执行。
     *
     * @param record 执行记录
     */
    void record(JobExecutionRecord record);
}
