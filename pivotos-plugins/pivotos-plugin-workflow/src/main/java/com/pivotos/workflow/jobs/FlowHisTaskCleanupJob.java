package com.pivotos.workflow.jobs;

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
import com.pivotos.starter.job.retention.TenantIdProvider;
import com.pivotos.workflow.mapper.FlowHisTaskRetentionMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.PostConstruct;
import org.dromara.warm.flow.orm.entity.FlowHisTask;
import org.dromara.warm.flow.orm.mapper.FlowHisTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 流程历史任务 {@code flow_his_task} 留存清理（v3.1.0 生产化 P1-B1）。
 * <p>
 * 本表是审批留痕的载体（每个节点每次办理写一条历史），流程一多增长很快，
 * 而 warm-flow 引擎自身<strong>只提供软删、没有留存清理能力</strong>。
 *
 * <h3>本类的分工是踩过坑之后定的</h3>
 * <ul>
 *   <li><b>查询复用引擎 Mapper</b>：{@code org.dromara.warm.flow.orm.mapper.FlowHisTaskMapper}
 *       （{@code WarmMapper<FlowHisTask>} extends {@code BaseMapper}）已由引擎注册，
 *       直接用它的 {@code selectPage} 取主键，与引擎共用一份实体映射，
 *       避免本仓再维护一个会随引擎版本漂移的表结构投影。
 *       （本仓曾自建同名 Mapper，撞 bean 名 {@code flowHisTaskMapper} 导致
 *       {@code ConflictingBeanDefinitionException}，启动直接失败。）</li>
 *   <li><b>删除走本仓显式 {@code DELETE}</b>：引擎实体的 {@code delFlag} 带 {@code @TableLogic}
 *       （javap 反编译 1.8.9 实证），{@code deleteBatchIds} 会被改写成
 *       {@code UPDATE SET del_flag=...}（软删，不回收空间），
 *       故用 {@link FlowHisTaskRetentionMapper#physicalDeleteByIds}。</li>
 *   <li><b>逐租户执行</b>：本表有 {@code tenant_id} 列（引擎侧 VARCHAR(40)），
 *       与 {@code msg_*} / {@code ai_*} 同款风险——租户上下文为空时
 *       {@code ColumnTenantStrategy#ignoreTable} 一律放行，一条 DELETE 即跨租户全表删除。</li>
 * </ul>
 *
 * <b>已知边界</b>：引擎实体的 {@code delFlag} 带 {@code @TableLogic}，所以 {@code selectPage}
 * 会自动过滤软删行——**已被引擎软删的历史行不在本清理范围内**（仍占空间）。
 * 这是沿引擎口径的取舍：不擅自改动引擎认定的「已删」语义。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@Component
public class FlowHisTaskCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(FlowHisTaskCleanupJob.class);

    /** XXL-Job handler 名 */
    public static final String HANDLER = "flowHisTaskCleanup";

    private static final String TABLE = "flow_his_task";

    private final FlowHisTaskMapper hisTaskMapper;
    private final FlowHisTaskRetentionMapper retentionMapper;
    private final RetentionProperties properties;
    private final ObjectProvider<TenantIdProvider> tenantProvider;
    private final ObjectProvider<JobExecutionRecorder> recorder;
    private final JobHandlerRegistry registry;
    private final RetentionJobRegistry retentionRegistry;

    public FlowHisTaskCleanupJob(FlowHisTaskMapper hisTaskMapper,
                                 FlowHisTaskRetentionMapper retentionMapper,
                                 RetentionProperties properties,
                                 ObjectProvider<TenantIdProvider> tenantProvider,
                                 ObjectProvider<JobExecutionRecorder> recorder,
                                 JobHandlerRegistry registry,
                                 RetentionJobRegistry retentionRegistry) {
        this.hisTaskMapper = hisTaskMapper;
        this.retentionMapper = retentionMapper;
        this.properties = properties;
        this.tenantProvider = tenantProvider;
        this.recorder = recorder;
        this.registry = registry;
        this.retentionRegistry = retentionRegistry;
    }

    /** 自注册：手动触发 + 本地兜底调度 */
    @PostConstruct
    public void registerManualTrigger() {
        registry.register(HANDLER, "流程历史清理", this::execute);
        retentionRegistry.register(HANDLER, "流程历史清理", this::execute);
    }

    /**
     * 流程历史任务清理（建议 cron: 0 40 3 * * ?）。
     */
    @XxlJob(HANDLER)
    public void execute() {
        JobExecutionHelper.run(HANDLER, recorder, this::doExecute);
    }

    private void doExecute() {
        int days = properties.getFlowHisTaskDays();
        // 引擎实体的 createTime 是 java.util.Date（不是 LocalDateTime），类型别用错
        Date cutoff = new Date(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000);
        TenantIdProvider tenants = tenantProvider.getIfAvailable();
        RetentionPurgeOps ops = new RetentionPurgeOps() {

            @Override
            public RetentionIdBatch loadExpiredIds(int size) {
                LambdaQueryWrapper<FlowHisTask> query = new LambdaQueryWrapper<>();
                query.lt(FlowHisTask::getCreateTime, cutoff).select(FlowHisTask::getId);
                Page<FlowHisTask> page = hisTaskMapper.selectPage(new Page<>(1, size), query);
                List<Long> ids = page.getRecords().stream()
                        .map(FlowHisTask::getId)
                        .filter(Objects::nonNull)
                        .toList();
                return new RetentionIdBatch(ids, page.getTotal());
            }

            @Override
            public int deleteByIds(List<Long> ids) {
                return retentionMapper.physicalDeleteByIds(ids);
            }
        };
        RetentionReport report = RetentionCleanupTemplate.runPerTenant(properties, TABLE, days, tenants, ops);
        log.info("[Job] 流程历史留存清理：{}（cutoff={}）", report.summary(), cutoff);
    }
}
