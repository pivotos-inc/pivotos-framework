package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.job.api.JobHandlerRegistry;
import com.pivotos.system.domain.dto.JobLogQuery;
import com.pivotos.system.domain.entity.SysJobLog;
import com.pivotos.system.mapper.SysJobLogMapper;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务执行记录（查询只读 + 手动触发）。
 * <p>
 * 手动触发走 JobHandlerRegistry（各 Job 自注册），与 XXL-Job 调度中心解耦，
 * 无调度中心的环境也能验证任务逻辑；执行记录由 Job 侧埋点自动落库。
 */
@RestController
@RequestMapping("/system/joblog")
@RequiredArgsConstructor
public class JobLogController {

    private final SysJobLogMapper jobLogMapper;
    private final JobHandlerRegistry jobHandlerRegistry;

    /** 执行记录分页 */
    @GetMapping("/page")
    @SaCheckPermission(value = "system:joblog:list", type = StpSysUtil.TYPE)
    public R<PageResult<SysJobLog>> page(JobLogQuery query) {
        Page<SysJobLog> page = jobLogMapper.selectPage(PageUtils.toMpPage(query),
                Wrappers.<SysJobLog>lambdaQuery()
                        .like(StringUtils.hasText(query.getJobHandler()), SysJobLog::getJobHandler, query.getJobHandler())
                        .eq(query.getStatus() != null, SysJobLog::getStatus, query.getStatus())
                        .ge(query.getBeginTime() != null, SysJobLog::getExecuteTime, query.getBeginTime())
                        .le(query.getEndTime() != null, SysJobLog::getExecuteTime, query.getEndTime())
                        .orderByDesc(SysJobLog::getExecuteTime));
        return R.ok(PageUtils.toPageResult(page, page.getRecords()));
    }

    /** 手动触发任务（同步执行，返回执行结果） */
    @PostMapping("/trigger/{handler}")
    @SaCheckPermission(value = "system:joblog:trigger", type = StpSysUtil.TYPE)
    public R<String> trigger(@PathVariable String handler) {
        var task = jobHandlerRegistry.find(handler);
        if (task.isEmpty()) {
            return R.fail(1500, "任务不存在或未注册: " + handler);
        }
        try {
            task.get().run();
            return R.ok("执行成功: " + handler);
        } catch (Exception e) {
            return R.fail(1500, "执行失败: " + e.getMessage());
        }
    }
}
