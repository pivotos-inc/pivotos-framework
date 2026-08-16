package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.job.api.JobHandlerInfo;
import com.pivotos.system.domain.dto.JobQuery;
import com.pivotos.system.domain.dto.JobSaveRequest;
import com.pivotos.system.domain.vo.JobVO;
import com.pivotos.system.service.SysJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定时任务管理（S89 定时任务控制台）。
 * <p>
 * sys_job 本地表 CRUD + XXL-Job Open API 同步；
 * 调度中心不可达时 CRUD 仍可用，同步降级为 warn 日志。
 */
@Tag(name = "定时任务", description = "定时任务管理 + XXL-Job 同步")
@RestController
@RequestMapping("/system/job")
@RequiredArgsConstructor
public class JobController {

    private final SysJobService sysJobService;

    /** 分页查询 */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    @SaCheckPermission(value = "system:job:list", type = StpSysUtil.TYPE)
    public R<PageResult<JobVO>> page(JobQuery query) {
        return R.ok(sysJobService.pageJobs(query));
    }

    /** 详情 */
    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:job:query", type = StpSysUtil.TYPE)
    public R<JobVO> get(@PathVariable Long id) {
        return R.ok(sysJobService.getJob(id));
    }

    /** 已注册 Handler 列表 */
    @Operation(summary = "已注册 Handler 列表")
    @GetMapping("/handlers")
    @SaCheckPermission(value = "system:job:list", type = StpSysUtil.TYPE)
    public R<List<JobHandlerInfo>> handlers() {
        return R.ok(sysJobService.listHandlers());
    }

    /** 新增 */
    @Operation(summary = "新增")
    @PostMapping
    @SaCheckPermission(value = "system:job:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody JobSaveRequest request) {
        return R.ok(sysJobService.createJob(request));
    }

    /** 修改 */
    @Operation(summary = "修改")
    @PutMapping
    @SaCheckPermission(value = "system:job:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody JobSaveRequest request) {
        sysJobService.updateJob(request);
        return R.ok();
    }

    /** 删除 */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:job:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        sysJobService.deleteJob(id);
        return R.ok();
    }

    /** 启停切换 */
    @Operation(summary = "启停切换")
    @PutMapping("/{id}/status")
    @SaCheckPermission(value = "system:job:changeStatus", type = StpSysUtil.TYPE)
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam int status) {
        sysJobService.changeStatus(id, status);
        return R.ok();
    }

    /** 手动触发 */
    @Operation(summary = "手动触发")
    @PostMapping("/{id}/trigger")
    @SaCheckPermission(value = "system:job:trigger", type = StpSysUtil.TYPE)
    public R<Void> trigger(@PathVariable Long id) {
        sysJobService.triggerJob(id);
        return R.ok();
    }

    /** 预览最近 5 次执行时间 */
    @Operation(summary = "预览最近 5 次执行时间")
    @GetMapping("/nextTriggerTime")
    @SaCheckPermission(value = "system:job:list", type = StpSysUtil.TYPE)
    public R<List<String>> nextTriggerTime(@RequestParam String scheduleType,
                                           @RequestParam String scheduleConf) {
        return R.ok(sysJobService.nextTriggerTime(scheduleType, scheduleConf));
    }
}
