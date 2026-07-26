package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.dto.DeptQuery;
import com.pivotos.system.domain.dto.DeptSaveRequest;
import com.pivotos.system.domain.vo.DeptVO;
import com.pivotos.system.service.DeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 部门管理 */
@RestController
@RequestMapping("/system/dept")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;

    /** 部门树查询（用户表单等部门下拉也用此接口，登录即可读） */
    @GetMapping("/tree")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<List<DeptVO>> tree(DeptQuery query) {
        return R.ok(deptService.treeDepts(query));
    }

    /** 详情 */
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:dept:query", type = StpSysUtil.TYPE)
    public R<DeptVO> get(@PathVariable Long id) {
        return R.ok(deptService.getDept(id));
    }

    /** 新增 */
    @PostMapping
    @SaCheckPermission(value = "system:dept:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody DeptSaveRequest request) {
        return R.ok(deptService.createDept(request));
    }

    /** 修改 */
    @PutMapping
    @SaCheckPermission(value = "system:dept:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody DeptSaveRequest request) {
        deptService.updateDept(request);
        return R.ok();
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:dept:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        deptService.deleteDept(id);
        return R.ok();
    }
}
