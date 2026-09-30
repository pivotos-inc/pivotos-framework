package com.pivotos.system.jobs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.starter.job.api.JobExecutionHelper;
import com.pivotos.starter.job.api.JobExecutionRecorder;
import com.pivotos.starter.job.api.JobHandlerRegistry;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.mapper.SysOperLogMapper;
import com.pivotos.system.search.OperLogSearchSupport;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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
    private final OperLogSearchSupport searchSupport;

    @Value("${pivotos.job.oper-log-retention-days:180}")
    private int retentionDays;

    public OperLogCleanupJob(SysOperLogMapper operLogMapper,
                             ObjectProvider<JobExecutionRecorder> recorder,
                             JobHandlerRegistry registry,
                             OperLogSearchSupport searchSupport) {
        this.operLogMapper = operLogMapper;
        this.recorder = recorder;
        this.registry = registry;
        this.searchSupport = searchSupport;
    }

    /** 自注册：支持管理端手动触发 */
    @PostConstruct
    public void registerManualTrigger() {
        registry.register(HANDLER, "操作日志清理", this::execute);
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
        // 先取主键再删表：索引侧只能按 id 删（S122 双写后索引与表必须同生共死，否则检索会命中已删数据）
        LambdaQueryWrapper<SysOperLog> idQuery = new LambdaQueryWrapper<>();
        idQuery.lt(SysOperLog::getOperTime, cutoffTime).select(SysOperLog::getId);
        List<Long> ids = operLogMapper.selectList(idQuery).stream()
                .map(SysOperLog::getId)
                .filter(Objects::nonNull)
                .toList();

        int deletedCount = 0;
        if (!ids.isEmpty()) {
            LambdaQueryWrapper<SysOperLog> deleteQuery = new LambdaQueryWrapper<>();
            deleteQuery.lt(SysOperLog::getOperTime, cutoffTime);
            deletedCount = operLogMapper.delete(deleteQuery);
        }
        searchSupport.deleteByIds(ids);

        log.info("[Job] OperLog cleanup done: deleted={}, cutoffTime={}", deletedCount, cutoffTime);
    }
}
