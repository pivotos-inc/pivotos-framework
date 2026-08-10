package com.pivotos.system.jobs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.starter.job.api.JobExecutionHelper;
import com.pivotos.starter.job.api.JobExecutionRecorder;
import com.pivotos.starter.job.api.JobHandlerRegistry;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.mapper.SysOperLogMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Operation log cleanup scheduled job.
 * <p>
 * Runs daily at 2:00 AM, deletes logs older than retention days (default 180).
 * Prevents sys_oper_log table from growing unbounded.
 * <p>
 * 每次执行写入 sys_job_log（S37）；自注册到 JobHandlerRegistry 支持手动触发。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Component
public class OperLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OperLogCleanupJob.class);

    /** XXL-Job handler 名 */
    public static final String HANDLER = "operLogCleanup";

    private final SysOperLogMapper operLogMapper;
    private final ObjectProvider<JobExecutionRecorder> recorder;
    private final JobHandlerRegistry registry;

    @Value("${pivotos.job.oper-log-retention-days:180}")
    private int retentionDays;

    public OperLogCleanupJob(SysOperLogMapper operLogMapper,
                             ObjectProvider<JobExecutionRecorder> recorder,
                             JobHandlerRegistry registry) {
        this.operLogMapper = operLogMapper;
        this.recorder = recorder;
        this.registry = registry;
    }

    /** 自注册：支持管理端手动触发 */
    @PostConstruct
    public void registerManualTrigger() {
        registry.register(HANDLER, this::execute);
    }

    /**
     * Operation log cleanup (suggested cron: 0 0 2 * * ?).
     */
    @XxlJob(HANDLER)
    public void execute() {
        JobExecutionHelper.run(HANDLER, recorder, this::doExecute);
    }

    private void doExecute() {
        log.info("[Job] OperLog cleanup started: retentionDays={}", retentionDays);

        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        LambdaQueryWrapper<SysOperLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(SysOperLog::getOperTime, cutoffTime);
        int deletedCount = operLogMapper.delete(wrapper);

        log.info("[Job] OperLog cleanup done: deleted={}, cutoffTime={}", deletedCount, cutoffTime);
    }
}
