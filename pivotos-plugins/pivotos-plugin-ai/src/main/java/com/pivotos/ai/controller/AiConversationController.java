package com.pivotos.ai.controller;

import com.pivotos.ai.domain.dto.ConversationRenameRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.ai.domain.vo.ChatMessageVO;
import com.pivotos.ai.domain.vo.ConversationVO;
import com.pivotos.ai.service.AiChatService;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 会话管理（个人会话，登录即可用；归属校验在 Service 层统一处理）
 */
@Tag(name = "AI 会话", description = "AI 会话管理")
@RestController
@RequestMapping("/ai/conversation")
@RequiredArgsConstructor
public class AiConversationController {

    private final AiChatService aiChatService;

    /** 我的会话列表（按最近活跃倒序） */
    @Operation(summary = "我的会话列表（按最近活跃倒序）")
    @GetMapping("/list")
    public R<List<ConversationVO>> list() {
        return R.ok(aiChatService.listConversations(requireUserId()));
    }

    /** 会话内消息历史（时间正序） */
    @Operation(summary = "会话内消息历史（时间正序）")
    @GetMapping("/{id}/messages")
    public R<List<ChatMessageVO>> messages(@PathVariable Long id) {
        return R.ok(aiChatService.listMessages(requireUserId(), id));
    }

    /** 删除会话（连带消息） */
    @Operation(summary = "删除会话（连带消息）")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        aiChatService.deleteConversation(requireUserId(), id);
        return R.ok();
    }

    /** 重命名会话（title 非空且 ≤128，DTO 层校验） */
    @Operation(summary = "重命名会话（title 非空且 ≤128，DTO 层校验）")
    @PutMapping("/{id}")
    public R<Void> rename(@PathVariable Long id, @Validated @RequestBody ConversationRenameRequest request) {
        aiChatService.renameConversation(requireUserId(), id, request.getTitle());
        return R.ok();
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
