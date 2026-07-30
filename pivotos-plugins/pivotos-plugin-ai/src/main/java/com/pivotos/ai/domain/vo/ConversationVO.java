package com.pivotos.ai.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 会话 VO（我的会话列表） */
@Data
public class ConversationVO {

    /** 会话ID */
    private Long id;

    /** 会话标题 */
    private String title;

    /** 模型标识 */
    private String model;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最近活跃时间 */
    private LocalDateTime updateTime;
}
