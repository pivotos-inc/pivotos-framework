package com.pivotos.ai.service;

import com.pivotos.ai.domain.dto.ApprovalAdviceRequest;
import com.pivotos.ai.domain.vo.ApprovalAdviceVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 审批建议服务（S101 A3）
 *
 * <p>聚合待办上下文 → 检索制度知识库 → 结构化建议生成（SSE 流式）→ 落库留痕。
 */
public interface AiApprovalAdviceService {

    /**
     * 流式生成审批建议（SSE：meta → delta* → done，异常 error）
     */
    SseEmitter streamAdvice(Long userId, ApprovalAdviceRequest request);

    /**
     * 最近一条建议回显（仅本人记录；无记录返回 null）
     */
    ApprovalAdviceVO latestAdvice(Long userId, Long taskId);
}
