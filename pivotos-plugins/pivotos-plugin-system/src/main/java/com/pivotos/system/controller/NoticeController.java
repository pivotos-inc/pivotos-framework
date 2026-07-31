package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.api.annotation.Log;
import com.pivotos.system.api.enums.OperType;
import com.pivotos.system.domain.dto.NoticeQuery;
import com.pivotos.system.domain.dto.NoticeSaveRequest;
import com.pivotos.system.domain.vo.NoticeVO;
import com.pivotos.system.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 通知公告（管理 CRUD + 发布/撤回 + 登录可读的已发布公告） */
@RestController
@RequestMapping("/system/notice")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    // ---------- 已发布公告（登录即可读，PC 首页卡片用） ----------

    /** 最新已发布公告 */
    @GetMapping("/published")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<List<NoticeVO>> listPublished(@RequestParam(defaultValue = "5") int limit) {
        return R.ok(noticeService.listPublished(limit));
    }

    /** 已发布公告详情 */
    @GetMapping("/published/{id}")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<NoticeVO> getPublished(@PathVariable Long id) {
        return R.ok(noticeService.getPublished(id));
    }

    // ---------- 管理端 ----------

    @GetMapping("/page")
    @SaCheckPermission(value = "system:notice:list", type = StpSysUtil.TYPE)
    public R<PageResult<NoticeVO>> page(NoticeQuery query) {
        return R.ok(noticeService.pageNotices(query));
    }

    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:notice:query", type = StpSysUtil.TYPE)
    public R<NoticeVO> get(@PathVariable Long id) {
        return R.ok(noticeService.getNotice(id));
    }

    @PostMapping
    @SaCheckPermission(value = "system:notice:add", type = StpSysUtil.TYPE)
    @Log(module = "通知公告", type = OperType.CREATE)
    public R<Long> create(@Validated @RequestBody NoticeSaveRequest request) {
        return R.ok(noticeService.createNotice(request));
    }

    @PutMapping
    @SaCheckPermission(value = "system:notice:edit", type = StpSysUtil.TYPE)
    @Log(module = "通知公告", type = OperType.UPDATE)
    public R<Void> update(@Validated @RequestBody NoticeSaveRequest request) {
        noticeService.updateNotice(request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:notice:remove", type = StpSysUtil.TYPE)
    @Log(module = "通知公告", type = OperType.DELETE)
    public R<Void> delete(@PathVariable Long id) {
        noticeService.deleteNotice(id);
        return R.ok();
    }

    @PutMapping("/{id}/publish")
    @SaCheckPermission(value = "system:notice:publish", type = StpSysUtil.TYPE)
    @Log(module = "通知公告", type = OperType.PUBLISH)
    public R<Void> publish(@PathVariable Long id) {
        noticeService.publishNotice(id);
        return R.ok();
    }

    @PutMapping("/{id}/revoke")
    @SaCheckPermission(value = "system:notice:publish", type = StpSysUtil.TYPE)
    @Log(module = "通知公告", type = OperType.REVOKE)
    public R<Void> revoke(@PathVariable Long id) {
        noticeService.revokeNotice(id);
        return R.ok();
    }
}
