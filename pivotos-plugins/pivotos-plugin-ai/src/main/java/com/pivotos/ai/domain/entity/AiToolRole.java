package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 工具角色白名单实体（S98 A2）
 *
 * <p>语义：工具无白名单记录 = 登录用户皆可调用；有记录则调用人角色须命中其一，
 * role_code='*' 为通配（等价全放行但保留显式登记痕迹）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_tool_role")
public class AiToolRole extends BaseDO {

    /** 工具 ID（ai_tool.id） */
    private Long toolId;

    /** 角色编码（* 通配所有登录用户） */
    private String roleCode;
}
