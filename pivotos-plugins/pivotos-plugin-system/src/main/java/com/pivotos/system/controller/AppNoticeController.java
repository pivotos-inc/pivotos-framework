package com.pivotos.system.controller;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.system.domain.vo.NoticeVO;
import com.pivotos.system.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 移动端通知公告接口（工作台公告栏 + 详情页，
 * 与 WorkbenchController 同惯例：三账号体系通用，登录态由 LoginContextFilter 统一解析）。
 */
@Tag(name = "App 通知公告", description = "移动端通知公告接口")
@RestController
@RequestMapping("/app/system/notice")
@RequiredArgsConstructor
public class AppNoticeController {

    private final NoticeService noticeService;

    /** 最新已发布公告（工作台公告栏） */
    @Operation(summary = "最新已发布公告（工作台公告栏）")
    @GetMapping("/published")
    public R<List<NoticeVO>> listPublished(@RequestParam(defaultValue = "3") int limit) {
        requireLogin();
        return R.ok(noticeService.listPublished(limit));
    }

    /** 已发布公告详情 */
    @Operation(summary = "已发布公告详情")
    @GetMapping("/{id}")
    public R<NoticeVO> get(@PathVariable Long id) {
        requireLogin();
        return R.ok(noticeService.getPublished(id));
    }

    private void requireLogin() {
        if (LoginContext.getUserId() == null) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
    }
}
