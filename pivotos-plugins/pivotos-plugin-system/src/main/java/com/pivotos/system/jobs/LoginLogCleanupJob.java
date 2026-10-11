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
import com.pivotos.system.domain.entity.SysLoginLog;
import com.pivotos.system.mapper.SysLoginLogMapper;
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
 * 登录日志留存清理（v3.1.0 生产化 P1-B1）。
 * <p>
 * {@code sys_login_log} 是<strong>平台共享表</strong>（无 tenant_id 列，已登记租户内置忽略表），
 * 每次登录写一条、只增不改，没有清理就会无限增长。
 * <p>
 * 执行链：{@code @XxlJob}（调度中心）或 {@code LocalRetentionScheduler}（中心不可达时的本地兜底）
 * → {@link JobExecutionHelper}（计时 + 落 sys_job_log）→ {@link RetentionCleanupTemplate}（安全带 + 报告）。
 * 同时自注册到 {@link JobHandlerRegistry} 供管理端手动触发、到 {@link RetentionJobRegistry} 供本地兜底遍历。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@Component
public class LoginLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(LoginLogCleanupJob.class);

    /** XXL-Job handler 名 */
    public static final String HANDLER = "loginLogCleanup";

    private static final String TABLE = "sys_login_log";

    private final SysLoginLogMapper loginLogMapper;
    private final RetentionProperties properties;
    private final ObjectProvider<JobExecutionRecorder> recorder;
    private final JobHandlerRegistry registry;
    private final RetentionJobRegistry retentionRegistry;

    public LoginLogCleanupJob(SysLoginLogMapper loginLogMapper,
                              RetentionProperties properties,
                              ObjectProvider<JobExecutionRecorder> recorder,
                              JobHandlerRegistry registry,
                              RetentionJobRegistry retentionRegistry) {
        this.loginLogMapper = loginLogMapper;
        this.properties = properties;
        this.recorder = recorder;
        this.registry = registry;
        this.retentionRegistry = retentionRegistry;
    }

    /** 自注册：手动触发 + 本地兜底调度 */
    @PostConstruct
    public void registerManualTrigger() {
        registry.register(HANDLER, "登录日志清理", this::execute);
        retentionRegistry.register(HANDLER, "登录日志清理", this::execute);
    }

    /**
     * 登录日志清理（建议 cron: 0 0 3 * * ?）。
     */
    @XxlJob(HANDLER)
    public void execute() {
        JobExecutionHelper.run(HANDLER, recorder, this::doExecute);
    }

    private void doExecute() {
        int days = properties.getLoginLogDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        RetentionPurgeOps ops = new RetentionPurgeOps() {

            @Override
            public RetentionIdBatch loadExpiredIds(int size) {
                // 时间列是 login_time（业务时间），不是 create_time
                LambdaQueryWrapper<SysLoginLog> query = new LambdaQueryWrapper<>();
                query.lt(SysLoginLog::getLoginTime, cutoff).select(SysLoginLog::getId);
                Page<SysLoginLog> page = loginLogMapper.selectPage(new Page<>(1, size), query);
                List<Long> ids = page.getRecords().stream()
                        .map(SysLoginLog::getId)
                        .filter(Objects::nonNull)
                        .toList();
                return new RetentionIdBatch(ids, page.getTotal());
            }

            @Override
            public int deleteByIds(List<Long> ids) {
                return loginLogMapper.physicalDeleteByIds(ids);
            }
        };
        RetentionReport report = RetentionCleanupTemplate.runGlobal(properties, TABLE, days, ops);
        log.info("[Job] 登录日志留存清理：{}（cutoff={}）", report.summary(), cutoff);
    }
}
