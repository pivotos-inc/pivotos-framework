package com.pivotos.ai.controller;

import com.pivotos.ai.domain.dto.ChatSendRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.ai.domain.vo.ChatMessageVO;
import com.pivotos.ai.kb.api.dto.KbOptionDTO;
import com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade;
import com.pivotos.ai.service.AiChatService;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;

/**
 * AI 对话（个人能力，登录即可用，与"我的消息"同款鉴权模式）。
 * 流式端点用 POST + SseEmitter：EventSource 带不了 Authorization 头，
 * 前端用 fetch + ReadableStream 消费。
 */
@Tag(name = "AI 对话", description = "AI 同步/流式对话")
@RestController
@RequestMapping("/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;
    /** 知识库门面（可选依赖：kb 插件未部署时返回空列表） */
    private final ObjectProvider<IKnowledgeBaseFacade> kbFacadeProvider;

    /** 同步对话（一次性返回完整回复） */
    @Operation(summary = "同步对话（一次性返回完整回复）")
    @PostMapping("/send")
    public R<ChatMessageVO> send(@Validated @RequestBody ChatSendRequest request) {
        return R.ok(aiChatService.send(requireUserId(), request));
    }

    /** 流式对话（SSE：meta → delta* → done，异常 error） */
    @Operation(summary = "AI 对话（SSE 流式）")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Validated @RequestBody ChatSendRequest request) {
        return aiChatService.stream(requireUserId(), request);
    }

    /**
     * 知识库下拉选项（对话页 RAG 选择用，登录即可访问；kb 插件未部署时返回空列表）。
     *
     * <p><b>v3.1.0 生产化 · A4：补上注释里写了、代码里没做的登录校验</b>。
     * 原实现方法体只读可选依赖、不碰登录上下文，而全项目鉴权是「注解驱动
     * + 业务代码取 {@code LoginContext} 兜底」的双轨制——不读登录上下文就自动匿名，
     * 实证匿名 {@code GET /ai/chat/kb-options} 直接 {@code code=0}。
     *
     * <p>刻意复用本控制器的 {@code requireUserId()} 而不是 {@code @SaCheckLogin}：
     * AI 对话是三体系能力（管端 / App / 智域），{@code @SaCheckLogin} 必须指定单一 realm，
     * 会把其余体系挡在外面；{@code requireUserId()} 与同控制器的 send / stream 完全同款，
     * 未登录抛 {@code 1002}，与 Sa-Token 未登录同码。
     */
    @Operation(summary = "知识库下拉选项（对话页 RAG 选择用，登录即可访问；kb 插件未部署时返回空列表）")
    @GetMapping("/kb-options")
    public R<List<KbOptionDTO>> kbOptions() {
        requireUserId();
        IKnowledgeBaseFacade facade = kbFacadeProvider.getIfAvailable();
        if (facade == null) {
            return R.ok(Collections.emptyList());
        }
        return R.ok(facade.listOptions());
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
