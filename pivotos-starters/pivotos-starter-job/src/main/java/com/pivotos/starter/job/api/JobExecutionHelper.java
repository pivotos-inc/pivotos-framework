package com.pivotos.starter.job.api;

import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;

/**
 * 任务执行记录辅助：计时 + 成败捕获 + 落记录（记录器缺失时静默跳过）。
 * <p>
 * 用法：{@code JobExecutionHelper.run("operLogCleanup", recorder, this::doExecute); }
 *
 * @author PivotOS
 * @since 2.2.0
 */
public final class JobExecutionHelper {

    private JobExecutionHelper() {
    }

    /**
     * 执行任务并记录。异常会先记失败再原样上抛（XXL-Job 侧仍判失败）。
     *
     * @param handler   XXL-Job handler 名
     * @param recorder  记录器（ObjectProvider 注入，允许无实现）
     * @param task      任务本体
     */
    public static void run(String handler, ObjectProvider<JobExecutionRecorder> recorder, Runnable task) {
        long start = System.currentTimeMillis();
        String errorMsg = null;
        try {
            task.run();
        } catch (RuntimeException e) {
            errorMsg = e.getMessage();
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - start;
            String msg = errorMsg;
            recorder.ifAvailable(r -> r.record(new JobExecutionRecord(
                    handler, msg == null, msg, duration, LocalDateTime.now())));
        }
    }
}
