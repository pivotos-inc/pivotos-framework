package com.pivotos.ai.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 工具注册视图（S98 A2）
 */
@Data
public class AiToolVO {

    /** 工具 ID */
    private Long id;

    /** 工具名（@Tool name） */
    private String toolName;

    /** 展示名 */
    private String displayName;

    /** 工具描述 */
    private String description;

    /** 工具类型（read/write） */
    private String toolType;

    /** 写操作是否需二次确认（0否 1是） */
    private Integer confirmRequired;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 来源（register=@Tool 扫描自动注册） */
    private String source;

    /** 注册时间 */
    private LocalDateTime createTime;

    /** 角色白名单（空 = 登录用户皆可调用） */
    private List<String> roles;
}
