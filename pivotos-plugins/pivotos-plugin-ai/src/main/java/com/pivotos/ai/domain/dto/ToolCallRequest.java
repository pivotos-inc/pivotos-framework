package com.pivotos.ai.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * REST 直连调用工具请求（S99 A2 双暴露）
 *
 * <p>args 与 MCP tools/call 的 arguments 同构（键值对，序列化为 JSON 后
 * 交给守卫式 ToolCallback），保证 REST/MCP 两条入口入参语义一致。
 */
@Data
public class ToolCallRequest {

    /** 工具名（ai_tool.tool_name） */
    @NotBlank(message = "工具名不能为空")
    @Schema(description = "工具名（ai_tool.tool_name）")
    private String toolName;

    /** 工具入参（键值对，写操作工具需含 confirm 标记） */
    @Schema(description = "工具入参（键值对，写操作工具需含 confirm 标记）")
    private Map<String, Object> args;
}
