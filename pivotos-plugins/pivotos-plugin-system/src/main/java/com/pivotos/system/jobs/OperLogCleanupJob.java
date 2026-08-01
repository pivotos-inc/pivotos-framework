package com.pivotos.system.jobs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.mapper.SysOperLogMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Operation log cleanup scheduled job.
 * <p>
 * Runs daily at 2:00 AM, deletes logs older than retention days (default 180).
 * Prevents sys_oper_log table from growing unbounded.
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Component
public class OperLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OperLogCleanupJob.class);

    private final SysOperLogMapper operLogMapper;

    @Value("${pivotos.job.oper-log-retention-days:180}")
    private int retentionDays;

    public OperLogCleanupJob(SysOperLogMapper operLogMapper) {
        this.operLogMapper = operLogMapper;
    }

    /**
     * Operation log cleanup (suggested cron: 0 0 2 * * ?).
     */
    @XxlJob("operLogCleanup")
    public void execute() {
        log.info("[Job] OperLog cleanup started: retentionDays={}", retentionDays);

        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        LambdaQueryWrapper<SysOperLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(SysOperLog::getOperTime, cutoffTime);
        int deletedCount = operLogMapper.delete(wrapper);

        log.info("[Job] OperLog cleanup done: deleted={}, cutoffTime={}", deletedCount, cutoffTime);
    }
}
