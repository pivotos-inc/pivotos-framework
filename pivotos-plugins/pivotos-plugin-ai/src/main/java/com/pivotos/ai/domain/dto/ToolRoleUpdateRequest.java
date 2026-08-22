package com.pivotos.ai.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * AI 工具角色白名单全量更新入参（S98 A2）
 *
 * <p>空列表语义 = 清空白名单（登录用户皆可调用）。
 */
@Data
public class ToolRoleUpdateRequest {

    /** 角色编码列表（'*' 为通配） */
    @NotNull(message = "角色列表不能为 null（清空白名单请传空数组）")
    private List<String> roles;
}
