package com.pivotos.starter.job.api;

import java.time.LocalDateTime;

/**
 * 定时任务单次执行记录（由 JobExecutionRecorder 持久化）。
 *
 * @param jobHandler XXL-Job handler 名
 * @param success    是否成功
 * @param errorMsg   失败时的异常信息（成功为 null）
 * @param durationMs 耗时（毫秒）
 * @param executeTime 执行开始时间
 * @author PivotOS
 * @since 2.2.0
 */
public record JobExecutionRecord(String jobHandler, boolean success, String errorMsg,
                                 long durationMs, LocalDateTime executeTime) {
}
