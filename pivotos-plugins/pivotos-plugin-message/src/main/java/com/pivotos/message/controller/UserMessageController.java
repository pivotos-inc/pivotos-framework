package com.pivotos.message.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.message.api.dto.MessageDTO;
import com.pivotos.message.api.dto.MessagePageQuery;
import com.pivotos.message.service.MessageService;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 我的消息（个人消息中心，登录即可访问） */
@RestController
@RequestMapping("/message/user")
@RequiredArgsConstructor
public class UserMessageController {

    private final MessageService messageService;

    @GetMapping("/page")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<PageResult<MessageDTO>> page(MessagePageQuery query) {
        return R.ok(messageService.pageByUser(LoginContext.getUserId(), query));
    }

    @PutMapping("/read/{userMessageId}")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<Void> read(@PathVariable Long userMessageId) {
        messageService.markRead(userMessageId, LoginContext.getUserId());
        return R.ok();
    }

    @PutMapping("/read-all")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<Integer> readAll() {
        return R.ok(messageService.markAllRead(LoginContext.getUserId()));
    }

    @GetMapping("/unread-count")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<Long> unreadCount() {
        return R.ok(messageService.countUnread(LoginContext.getUserId()));
    }
}
