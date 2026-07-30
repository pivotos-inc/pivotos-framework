package com.pivotos.ai.controller;

import com.pivotos.ai.domain.dto.ChatSendRequest;
import com.pivotos.ai.domain.vo.ChatMessageVO;
import com.pivotos.ai.service.AiChatService;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 对话（个人能力，登录即可用，与"我的消息"同款鉴权模式）。
 * 流式端点用 POST + SseEmitter：EventSource 带不了 Authorization 头，
 * 前端用 fetch + ReadableStream 消费。
 */
@RestController
@RequestMapping("/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    /** 同步对话（一次性返回完整回复） */
    @PostMapping("/send")
    public R<ChatMessageVO> send(@Validated @RequestBody ChatSendRequest request) {
        return R.ok(aiChatService.send(requireUserId(), request));
    }

    /** 流式对话（SSE：meta → delta* → done，异常 error） */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Validated @RequestBody ChatSendRequest request) {
        return aiChatService.stream(requireUserId(), request);
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
