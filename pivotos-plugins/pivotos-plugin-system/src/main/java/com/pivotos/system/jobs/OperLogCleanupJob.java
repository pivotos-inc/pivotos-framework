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
 * <p>
 * <b>v3.1.0 P1-B1 改造两处（行为有变，故写明）</b>：
 * <ol>
 *   <li><b>从逻辑删除改为物理删除</b>：原先 {@code operLogMapper.delete(query)} 走
 *       {@code BaseDO} 的 {@code @TableLogic}，实际是 {@code UPDATE SET deleted=1}——
 *       <strong>行数不变、空间不回收</strong>，「清理」只是把数据标成已删，表照样涨。
 *       现改为 {@code physicalDeleteByIds} 显式 {@code DELETE}。
 *       补上的安全带由 {@link RetentionCleanupTemplate} 统一提供（保留天数硬下限 /
 *       单次删除上限 / dry-run / 总开关）。</li>
 *   <li><b>保留天数改为统一口径</b>：优先读 {@code pivotos.retention.oper-log-days}，
 *       回落旧键 {@code pivotos.job.oper-log-retention-days}（已上线的配置继续生效，
 *       零改造成本；新配置优先）。</li>
 * </ol>
 * 索引侧同步不变：本表在 ES 有副本（S122 双写），删表必须同步删索引，否则检索命中已删数据。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Component
public class OperLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OperLogCleanupJob.class);

    /** XXL-Job handler 名 */
    public static final String HANDLER = "operLogCleanup";

    private static final String TABLE = "sys_oper_log";

    private final SysOperLogMapper operLogMapper;
    private final ObjectProvider<JobExecutionRecorder> recorder;
    private final JobHandlerRegistry registry;
    private final RetentionJobRegistry retentionRegistry;
    private final RetentionProperties properties;
    private final OperLogSearchSupport searchSupport;

    /**
     * 保留天数：新键优先、旧键回落。
     * <p>{@code pivotos.job.oper-log-retention-days} 是 2.2.0 起的既有键，
     * 直接换名会让已上线的配置静默失效（回落默认值 180 而不是用户配的值），故双层兜底。
     */
    @Value("${pivotos.retention.oper-log-days:${pivotos.job.oper-log-retention-days:180}}")
    private int retentionDays;

    public OperLogCleanupJob(SysOperLogMapper operLogMapper,
                             ObjectProvider<JobExecutionRecorder> recorder,
                             JobHandlerRegistry registry,
                             RetentionJobRegistry retentionRegistry,
                             RetentionProperties properties,
                             OperLogSearchSupport searchSupport) {
        this.operLogMapper = operLogMapper;
        this.recorder = recorder;
        this.registry = registry;
        this.retentionRegistry = retentionRegistry;
        this.properties = properties;
        this.searchSupport = searchSupport;
    }

    /** 自注册：手动触发 + 本地兜底调度 */
    @PostConstruct
    public void registerManualTrigger() {
        registry.register(HANDLER, "操作日志清理", this::execute);
        retentionRegistry.register(HANDLER, "操作日志清理", this::execute);
    }

    /**
     * Operation log cleanup (suggested cron: 0 0 2 * * ?).
     */
    @XxlJob(HANDLER)
    public void execute() {
        JobExecutionHelper.run(HANDLER, recorder, this::doExecute);
    }

    private void doExecute() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        RetentionPurgeOps ops = new RetentionPurgeOps() {

            @Override
            public RetentionIdBatch loadExpiredIds(int size) {
                // 先取主键再删表：索引侧只能按 id 删（S122 双写后索引与表必须同生共死）
                LambdaQueryWrapper<SysOperLog> query = new LambdaQueryWrapper<>();
                query.lt(SysOperLog::getOperTime, cutoffTime).select(SysOperLog::getId);
                Page<SysOperLog> page = operLogMapper.selectPage(new Page<>(1, size), query);
                List<Long> ids = page.getRecords().stream()
                        .map(SysOperLog::getId)
                        .filter(Objects::nonNull)
                        .toList();
                return new RetentionIdBatch(ids, page.getTotal());
            }

            @Override
            public int deleteByIds(List<Long> ids) {
                int deleted = operLogMapper.physicalDeleteByIds(ids);
                // 索引同步放在删表之后：宁可索引多留一会儿，也不能表删了索引还在（会命中已删数据）
                searchSupport.deleteByIds(ids);
                return deleted;
            }
        };
        RetentionReport report = RetentionCleanupTemplate.runGlobal(properties, TABLE, retentionDays, ops);
        log.info("[Job] 操作日志留存清理：{}（cutoff={}）", report.summary(), cutoffTime);
    }
}
