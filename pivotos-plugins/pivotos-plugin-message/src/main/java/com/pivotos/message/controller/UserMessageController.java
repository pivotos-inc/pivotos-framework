package com.pivotos.message.controller;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.message.api.dto.MessageDTO;
import com.pivotos.message.api.dto.MessagePageQuery;
import com.pivotos.message.service.MessageService;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的消息（个人消息中心，登录即可访问）。
 * sys / app / wx-mini 三账号体系通用：登录态由 LoginContextFilter 统一解析，
 * 这里只校验"已登录"，PC 与移动端共用同一个消息中心。
 */
@Tag(name = "我的消息", description = "个人消息中心")
@RestController
@RequestMapping("/message/user")
@RequiredArgsConstructor
public class UserMessageController {

    private final MessageService messageService;

    @Operation(summary = "我的消息分页")
    @GetMapping("/page")
    public R<PageResult<MessageDTO>> page(MessagePageQuery query) {
        return R.ok(messageService.pageByUser(requireUserId(), query));
    }

    @Operation(summary = "标记已读")
    @PutMapping("/read/{userMessageId}")
    public R<Void> read(@PathVariable Long userMessageId) {
        messageService.markRead(userMessageId, requireUserId());
        return R.ok();
    }

    @Operation(summary = "全部已读")
    @PutMapping("/read-all")
    public R<Integer> readAll() {
        return R.ok(messageService.markAllRead(requireUserId()));
    }

    @Operation(summary = "未读消息数")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(messageService.countUnread(requireUserId()));
    }

    /** 三体系统一登录校验（未登录 → 1002，与 Sa-Token 未登录同码） */
    private Long requireUserId() {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
