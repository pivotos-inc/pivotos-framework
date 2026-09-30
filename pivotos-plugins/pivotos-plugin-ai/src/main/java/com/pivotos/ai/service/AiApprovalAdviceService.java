package com.pivotos.ai.service;

import com.pivotos.ai.domain.dto.ApprovalAdviceRequest;
import com.pivotos.ai.domain.vo.ApprovalAdviceVO;
import com.pivotos.ai.domain.vo.AutoApprovalResultVO;
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

    /**
     * 受控自动预审（A4E / S117）：确定性低风险规则全中则自动通过并留痕，默认关闭。
     *
     * <p>必须在审批人本人请求内调用——归属闸取自 LoginContext（只读 ScopedValue），
     * 服务端无法以审批人身份伪造上下文。
     */
    AutoApprovalResultVO autoPass(Long userId, Long taskId);
}
