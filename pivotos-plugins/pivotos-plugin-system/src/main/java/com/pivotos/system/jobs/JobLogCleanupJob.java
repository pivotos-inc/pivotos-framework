package com.pivotos.system.jobs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.starter.job.api.JobExecutionHelper;
import com.pivotos.starter.job.api.JobExecutionRecorder;
import com.pivotos.starter.job.api.JobHandlerRegistry;
import com.pivotos.starter.job.retention.RetentionCleanupTemplate;
import com.pivotos.starter.job.retention.RetentionIdBatch;
import com.pivotos.starter.job.retention.RetentionJobRegistry;
import com.pivotos.starter.job.retention.RetentionProperties;
import com.pivotos.starter.job.retention.RetentionPurgeOps;
import com.pivotos.starter.job.retention.RetentionReport;
import com.pivotos.system.domain.entity.SysJobLog;
import com.pivotos.system.mapper.SysJobLogMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 任务执行日志 {@code sys_job_log} 留存清理（v3.1.0 生产化 P1-B1）。
 * <p>
 * 本表是<strong>平台共享表</strong>（无 tenant_id 列，已登记租户内置忽略表），
 * 每个任务每次执行写一条，任务一多增长很快。
 * <p>
 * <b>自指问题的处置（本类最需要说明的一点）</b>：清理任务自己也会往这张表写执行记录。
 * 顺序是「先删历史 → 后写本次」（{@link JobExecutionHelper} 在 {@code finally} 落记录），
 * 本次记录的 {@code execute_time} 是删除之后的时间戳，永远不会被自己这一刀砍到；
 * 再叠加保留天数硬下限（默认 ≥ 1 天，见 {@link RetentionProperties#getMinRetentionDays()}），
 * 即便把保留天数配到极小，也只会被红线拦下而不是真的自杀。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@Component
public class JobLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(JobLogCleanupJob.class);

    /** XXL-Job handler 名 */
    public static final String HANDLER = "jobLogCleanup";

    private static final String TABLE = "sys_job_log";

    private final SysJobLogMapper jobLogMapper;
    private final RetentionProperties properties;
    private final ObjectProvider<JobExecutionRecorder> recorder;
    private final JobHandlerRegistry registry;
    private final RetentionJobRegistry retentionRegistry;

    public JobLogCleanupJob(SysJobLogMapper jobLogMapper,
                            RetentionProperties properties,
                            ObjectProvider<JobExecutionRecorder> recorder,
                            JobHandlerRegistry registry,
                            RetentionJobRegistry retentionRegistry) {
        this.jobLogMapper = jobLogMapper;
        this.properties = properties;
        this.recorder = recorder;
        this.registry = registry;
        this.retentionRegistry = retentionRegistry;
    }

    /** 自注册：手动触发 + 本地兜底调度 */
    @PostConstruct
    public void registerManualTrigger() {
        registry.register(HANDLER, "任务执行日志清理", this::execute);
        retentionRegistry.register(HANDLER, "任务执行日志清理", this::execute);
    }

    /**
     * 任务执行日志清理（建议 cron: 0 10 3 * * ?）。
     */
    @XxlJob(HANDLER)
    public void execute() {
        JobExecutionHelper.run(HANDLER, recorder, this::doExecute);
    }

    private void doExecute() {
        int days = properties.getJobLogDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        RetentionPurgeOps ops = new RetentionPurgeOps() {

            @Override
            public RetentionIdBatch loadExpiredIds(int size) {
                // 时间列是 execute_time（任务实际执行时刻），不是 create_time
                LambdaQueryWrapper<SysJobLog> query = new LambdaQueryWrapper<>();
                query.lt(SysJobLog::getExecuteTime, cutoff).select(SysJobLog::getId);
                Page<SysJobLog> page = jobLogMapper.selectPage(new Page<>(1, size), query);
                List<Long> ids = page.getRecords().stream()
                        .map(SysJobLog::getId)
                        .filter(Objects::nonNull)
                        .toList();
                return new RetentionIdBatch(ids, page.getTotal());
            }

            @Override
            public int deleteByIds(List<Long> ids) {
                return jobLogMapper.physicalDeleteByIds(ids);
            }
        };
        RetentionReport report = RetentionCleanupTemplate.runGlobal(properties, TABLE, days, ops);
        log.info("[Job] 任务执行日志留存清理：{}（cutoff={}）", report.summary(), cutoff);
    }
}
