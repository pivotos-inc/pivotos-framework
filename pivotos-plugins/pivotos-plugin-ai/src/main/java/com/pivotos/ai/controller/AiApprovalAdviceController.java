package com.pivotos.ai.controller;

import com.pivotos.ai.domain.dto.ApprovalAdviceRequest;
import com.pivotos.ai.domain.vo.ApprovalAdviceVO;
import com.pivotos.ai.service.AiApprovalAdviceService;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.LoginContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 审批助手（S101 A3，登录即可用，审批人归属闸在 workflow 聚合侧）。
 * 流式端点用 POST + SseEmitter：EventSource 带不了 Authorization 头，
 * 前端用 fetch + ReadableStream 消费（姿势同对话流式端点）。
 */
@Tag(name = "AI 审批助手", description = "待办审批一键生成 AI 审批建议")
@RestController
@RequestMapping("/ai/approval")
@RequiredArgsConstructor
public class AiApprovalAdviceController {

    private final AiApprovalAdviceService approvalAdviceService;

    /** 流式生成审批建议（SSE：meta → delta* → done，异常 error） */
    @Operation(summary = "流式生成审批建议（SSE）")
    @PostMapping(value = "/advice/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Validated @RequestBody ApprovalAdviceRequest request) {
        return approvalAdviceService.streamAdvice(requireUserId(), request);
    }

    /** 最近一条建议回显（仅本人记录；无记录 data 为 null） */
    @Operation(summary = "最近一条审批建议回显")
    @GetMapping("/advice/{taskId}/latest")
    public R<ApprovalAdviceVO> latest(@PathVariable Long taskId) {
        return R.ok(approvalAdviceService.latestAdvice(requireUserId(), taskId));
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
