package com.pivotos.ai.domain.vo;

import lombok.Data;

/**
 * AI 用量按用户聚合 VO（S92）
 */
@Data
public class AiUsageUserVO {

    /** 用户 ID（未登录链路为 null） */
    private Long userId;

    /** 用户名（服务层回填） */
    private String username;

    /** 昵称（服务层回填） */
    private String nickname;

    /** 调用次数 */
    private Long calls;

    /** 总 token 合计 */
    private Long totalTokens;
}
