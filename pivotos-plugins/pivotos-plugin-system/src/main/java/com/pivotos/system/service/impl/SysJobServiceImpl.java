package com.pivotos.system.service.impl;

import com.google.gson.JsonObject;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.job.api.JobHandlerInfo;
import com.pivotos.starter.job.api.JobHandlerRegistry;
import com.pivotos.starter.job.client.XxlJobAdminClient;
import com.pivotos.starter.job.client.XxlJobPageResult;
import com.pivotos.starter.job.client.XxlJobResult;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.domain.dto.JobQuery;
import com.pivotos.system.domain.dto.JobSaveRequest;
import com.pivotos.system.domain.vo.JobVO;
import com.pivotos.system.service.SysJobService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 定时任务管理实现（以 XXL-Job admin 为单一数据源）。
 * <p>
 * 列表查询、CRUD、启停、触发全部通过 XxlJobAdminClient 调用 XXL-Job admin Open API。
 * 调度中心不可达时抛 JOB_ADMIN_UNREACHABLE 异常。
 *
 * @author PivotOS
 * @since 2.11.0
 */
@Service
@RequiredArgsConstructor
public class SysJobServiceImpl implements SysJobService {

    private static final Logger log = LoggerFactory.getLogger(SysJobServiceImpl.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int STATUS_PAUSED = 0;
    private static final int STATUS_RUNNING = 1;

    private static final String SCHEDULE_CRON = "CRON";
    private static final String SCHEDULE_FIX_RATE = "FIX_RATE";

    private final ObjectProvider<XxlJobAdminClient> xxlJobAdminClientProvider;
    private final JobHandlerRegistry jobHandlerRegistry;

    @Override
    public PageResult<JobVO> pageJobs(JobQuery query) {
        XxlJobAdminClient client = requireClient();

        int offset = (query.getPageNum() - 1) * query.getPageSize();
        XxlJobPageResult result = client.pageListJob(
                offset, query.getPageSize(),
                query.getJobName(),
                query.getTriggerStatus() != null ? query.getTriggerStatus() : -1
        );

        if (!result.success()) {
            throw new ServiceException(SystemErrorCode.JOB_ADMIN_UNREACHABLE);
        }

        List<JobVO> list = result.data().stream()
                .map(this::toJobVO)
                .toList();

        return new PageResult<>(list, (long) result.total(), query.getPageNum(), query.getPageSize());
    }

    @Override
    public JobVO getJob(Long id) {
        XxlJobAdminClient client = requireClient();

        int jobId = id.intValue();
        JsonObject obj = findJobById(client, jobId);
        if (obj == null) {
            throw new ServiceException(SystemErrorCode.JOB_NOT_FOUND);
        }
        return toJobVO(obj);
    }

    @Override
    public Long createJob(JobSaveRequest request) {
        validateSchedule(request);
        applyDefaults(request);
        XxlJobAdminClient client = requireClient();

        XxlJobResult result = client.addJob(
                request.getJobName(), request.getJobHandler(), request.getExecutorParam(),
                request.getScheduleType(), request.getScheduleConf(),
                request.getMisfireStrategy(), request.getExecutorRouteStrategy(),
                request.getExecutorBlockStrategy(), request.getExecutorTimeout(),
                request.getExecutorFailRetryCount());

        if (!result.success()) {
            throw new ServiceException(SystemErrorCode.JOB_SYNC_FAILED);
        }

        return result.jobId() != null ? result.jobId().longValue() : null;
    }

    @Override
    public void updateJob(JobSaveRequest request) {
        validateSchedule(request);
        applyDefaults(request);
        XxlJobAdminClient client = requireClient();

        int jobId = request.getId().intValue();
        JsonObject existing = findJobById(client, jobId);
        if (existing == null) {
            throw new ServiceException(SystemErrorCode.JOB_NOT_FOUND);
        }

        if (getInt(existing, "triggerStatus", 0) == STATUS_RUNNING) {
            throw new ServiceException(SystemErrorCode.JOB_STATUS_RUNNING);
        }

        XxlJobResult result = client.updateJob(
                jobId, request.getJobName(), request.getJobHandler(),
                request.getExecutorParam(), request.getScheduleType(), request.getScheduleConf(),
                request.getMisfireStrategy(), request.getExecutorRouteStrategy(),
                request.getExecutorBlockStrategy(), request.getExecutorTimeout(),
                request.getExecutorFailRetryCount());

        if (!result.success()) {
            throw new ServiceException(SystemErrorCode.JOB_SYNC_FAILED);
        }
    }

    @Override
    public void deleteJob(Long id) {
        XxlJobAdminClient client = requireClient();

        int jobId = id.intValue();
        JsonObject existing = findJobById(client, jobId);
        if (existing == null) {
            throw new ServiceException(SystemErrorCode.JOB_NOT_FOUND);
        }

        if (getInt(existing, "triggerStatus", 0) == STATUS_RUNNING) {
            throw new ServiceException(SystemErrorCode.JOB_STATUS_RUNNING);
        }

        XxlJobResult result = client.removeJob(jobId);
        if (!result.success()) {
            throw new ServiceException(SystemErrorCode.JOB_SYNC_FAILED);
        }
    }

    @Override
    public void changeStatus(Long id, int status) {
        XxlJobAdminClient client = requireClient();
        int jobId = id.intValue();

        XxlJobResult result = status == STATUS_RUNNING
                ? client.startJob(jobId)
                : client.stopJob(jobId);

        if (!result.success()) {
            throw new ServiceException(SystemErrorCode.JOB_SYNC_FAILED);
        }
    }

    @Override
    public void triggerJob(Long id) {
        XxlJobAdminClient client = requireClient();
        int jobId = id.intValue();

        XxlJobResult result = client.triggerJob(jobId, null);
        if (!result.success()) {
            throw new ServiceException(SystemErrorCode.JOB_SYNC_FAILED);
        }
    }

    @Override
    public List<JobHandlerInfo> listHandlers() {
        List<JobHandlerInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 从 XXL-Job admin 的已有任务中提取 handler
        XxlJobAdminClient client = xxlJobAdminClientProvider.getIfAvailable();
        if (client != null) {
            XxlJobPageResult pageResult = client.pageListJob(0, 10000, "", -1);
            if (pageResult.success()) {
                for (JsonObject obj : pageResult.data()) {
                    String handler = getString(obj, "executorHandler");
                    if (StringUtils.hasText(handler) && seen.add(handler)) {
                        result.add(new JobHandlerInfo(handler, handler));
                    }
                }
            }
        }

        // 合并本地注册的 handler
        for (JobHandlerInfo info : jobHandlerRegistry.list()) {
            if (seen.add(info.handler())) {
                result.add(info);
            }
        }

        return result;
    }

    @Override
    public List<String> nextTriggerTime(String scheduleType, String scheduleConf) {
        if (!StringUtils.hasText(scheduleType) || !StringUtils.hasText(scheduleConf)) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        try {
            if (SCHEDULE_CRON.equals(scheduleType)) {
                // Spring CronExpression 不支持 ?，替换为 *
                String cron = scheduleConf.replace('?', '*');
                CronExpression expression = CronExpression.parse(cron);
                LocalDateTime next = LocalDateTime.now();
                for (int i = 0; i < 5; i++) {
                    next = expression.next(next);
                    if (next == null) {
                        break;
                    }
                    result.add(next.format(FMT));
                }
            } else if (SCHEDULE_FIX_RATE.equals(scheduleType)) {
                int interval = Integer.parseInt(scheduleConf);
                if (interval <= 0) {
                    return List.of();
                }
                LocalDateTime next = LocalDateTime.now();
                for (int i = 0; i < 5; i++) {
                    next = next.plusSeconds(interval);
                    result.add(next.format(FMT));
                }
            }
        } catch (Exception e) {
            throw new ServiceException(SystemErrorCode.JOB_SCHEDULE_INVALID);
        }
        return result;
    }

    // ---------- internal ----------

    private XxlJobAdminClient requireClient() {
        XxlJobAdminClient client = xxlJobAdminClientProvider.getIfAvailable();
        if (client == null) {
            throw new ServiceException(SystemErrorCode.JOB_ADMIN_UNREACHABLE);
        }
        return client;
    }

    /**
     * 通过 pageListJob 查找指定 ID 的任务（XXL-Job admin 没有单独的 getById Open API）。
     */
    private JsonObject findJobById(XxlJobAdminClient client, int jobId) {
        XxlJobPageResult pageResult = client.pageListJob(0, 10000, "", -1);
        if (!pageResult.success()) {
            throw new ServiceException(SystemErrorCode.JOB_ADMIN_UNREACHABLE);
        }
        Optional<JsonObject> match = pageResult.data().stream()
                .filter(obj -> obj.has("id") && obj.get("id").getAsInt() == jobId)
                .findFirst();
        return match.orElse(null);
    }

    private void validateSchedule(JobSaveRequest request) {
        String type = request.getScheduleType();
        String conf = request.getScheduleConf();
        if (SCHEDULE_CRON.equals(type)) {
            if (!StringUtils.hasText(conf)) {
                throw new ServiceException(SystemErrorCode.JOB_SCHEDULE_INVALID);
            }
            try {
                CronExpression.parse(conf.replace('?', '*'));
            } catch (IllegalArgumentException e) {
                throw new ServiceException(SystemErrorCode.JOB_SCHEDULE_INVALID);
            }
        } else if (SCHEDULE_FIX_RATE.equals(type)) {
            if (!StringUtils.hasText(conf)) {
                throw new ServiceException(SystemErrorCode.JOB_SCHEDULE_INVALID);
            }
            try {
                int rate = Integer.parseInt(conf);
                if (rate <= 0) {
                    throw new ServiceException(SystemErrorCode.JOB_SCHEDULE_INVALID);
                }
            } catch (NumberFormatException e) {
                throw new ServiceException(SystemErrorCode.JOB_SCHEDULE_INVALID);
            }
        }
    }

    private void applyDefaults(JobSaveRequest request) {
        if (!StringUtils.hasText(request.getMisfireStrategy())) {
            request.setMisfireStrategy("FIRE_ONCE_NOW");
        }
        if (!StringUtils.hasText(request.getExecutorRouteStrategy())) {
            request.setExecutorRouteStrategy("ROUND");
        }
        if (!StringUtils.hasText(request.getExecutorBlockStrategy())) {
            request.setExecutorBlockStrategy("SERIAL_EXECUTION");
        }
        if (request.getExecutorTimeout() == null) {
            request.setExecutorTimeout(0);
        }
        if (request.getExecutorFailRetryCount() == null) {
            request.setExecutorFailRetryCount(0);
        }
    }

    private JobVO toJobVO(JsonObject obj) {
        JobVO vo = new JobVO();
        vo.setId((long) getInt(obj, "id", 0));
        vo.setJobName(getString(obj, "name"));
        vo.setJobHandler(getString(obj, "executorHandler"));
        vo.setScheduleType(getString(obj, "scheduleType"));
        vo.setScheduleConf(getString(obj, "scheduleConf"));
        vo.setExecutorParam(getString(obj, "executorParam"));
        vo.setMisfireStrategy(getString(obj, "misfireStrategy"));
        vo.setExecutorRouteStrategy(getString(obj, "executorRouteStrategy"));
        vo.setExecutorBlockStrategy(getString(obj, "executorBlockStrategy"));
        vo.setExecutorTimeout(getInt(obj, "executorTimeout", 0));
        vo.setExecutorFailRetryCount(getInt(obj, "executorFailRetryCount", 0));
        vo.setTriggerStatus(getInt(obj, "triggerStatus", 0));
        vo.setTriggerNextTime(getLong(obj, "triggerNextTime"));
        vo.setTriggerLastTime(getLong(obj, "triggerLastTime"));
        vo.setXxlJobId(getInt(obj, "id", 0));
        return vo;
    }

    private String getString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private int getInt(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return defaultValue;
    }

    private Long getLong(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsLong();
        }
        return null;
    }
}
