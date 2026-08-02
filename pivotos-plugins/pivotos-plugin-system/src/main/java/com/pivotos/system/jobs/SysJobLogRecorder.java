package com.pivotos.system.jobs;

import com.pivotos.starter.job.api.JobExecutionRecord;
import com.pivotos.starter.job.api.JobExecutionRecorder;
import com.pivotos.system.domain.entity.SysJobLog;
import com.pivotos.system.mapper.SysJobLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务执行记录落库实现（sys_job_log）。
 * <p>
 * 落库失败只告警不上抛，避免记录动作反过来打挂任务本体。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Component
public class SysJobLogRecorder implements JobExecutionRecorder {

    private static final Logger log = LoggerFactory.getLogger(SysJobLogRecorder.class);

    private final SysJobLogMapper jobLogMapper;

    public SysJobLogRecorder(SysJobLogMapper jobLogMapper) {
        this.jobLogMapper = jobLogMapper;
    }

    @Override
    public void record(JobExecutionRecord record) {
        try {
            SysJobLog entity = new SysJobLog();
            entity.setJobHandler(record.jobHandler());
            entity.setStatus(record.success() ? 0 : 1);
            entity.setErrorMsg(record.errorMsg());
            entity.setDuration(record.durationMs());
            entity.setExecuteTime(record.executeTime());
            jobLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[Job] 任务执行记录落库失败: handler={}, err={}", record.jobHandler(), e.getMessage());
        }
    }
}
