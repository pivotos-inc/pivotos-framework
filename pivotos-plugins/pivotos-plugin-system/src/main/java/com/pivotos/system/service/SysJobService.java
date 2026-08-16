package com.pivotos.system.service;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.job.api.JobHandlerInfo;
import com.pivotos.system.domain.dto.JobQuery;
import com.pivotos.system.domain.dto.JobSaveRequest;
import com.pivotos.system.domain.vo.JobVO;

import java.util.List;

/** 定时任务管理服务（S89 定时任务控制台） */
public interface SysJobService {

    /** 分页查询 */
    PageResult<JobVO> pageJobs(JobQuery query);

    /** 详情 */
    JobVO getJob(Long id);

    /** 新增（写 sys_job + 同步 XXL-Job addJob） */
    Long createJob(JobSaveRequest request);

    /** 修改（更新 sys_job + 同步 updateJob） */
    void updateJob(JobSaveRequest request);

    /** 删除（删 sys_job + 同步 removeJob） */
    void deleteJob(Long id);

    /** 启停切换（更新 trigger_status + 同步 startJob/stopJob） */
    void changeStatus(Long id, int status);

    /** 手动触发（优先走 XXL-Job triggerJob，无调度中心走本地 JobHandlerRegistry） */
    void triggerJob(Long id);

    /** 已注册 Handler 列表 */
    List<JobHandlerInfo> listHandlers();

    /** 预览最近 5 次执行时间 */
    List<String> nextTriggerTime(String scheduleType, String scheduleConf);
}
