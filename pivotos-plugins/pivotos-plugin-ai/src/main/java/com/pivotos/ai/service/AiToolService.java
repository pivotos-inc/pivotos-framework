package com.pivotos.ai.service;

import com.pivotos.ai.domain.dto.AiToolInvokeQuery;
import com.pivotos.ai.domain.dto.AiToolQuery;
import com.pivotos.ai.domain.vo.AiToolInvokeVO;
import com.pivotos.ai.domain.vo.AiToolVO;
import com.pivotos.common.core.page.PageResult;

import java.util.List;

/**
 * AI 工具注册管理服务（S98 A2）
 *
 * <p>职责：启动同步（@Tool 扫描 upsert ai_tool）、工具分页查询、
 * 角色白名单维护、停用/启用、调用审计分页。
 */
public interface AiToolService {

    /**
     * 同步工具注册表：容器内全部 ToolCallbackProvider 汇聚结果为基准，
     * 新工具落默认元数据（read + 无需确认 + 启用），已存在仅刷新描述与
     * 代码侧 @AiToolMeta 声明，代码中已移除的 register 来源工具逻辑删除。
     */
    void syncRegisteredTools();

    /** 工具分页查询（附角色白名单） */
    PageResult<AiToolVO> pageTools(AiToolQuery query);

    /**
     * 全量更新工具角色白名单（空列表 = 登录用户皆可调用）
     *
     * @param toolId 工具 ID
     * @param roles  角色编码列表（'*' 为通配）
     */
    void updateRoleWhitelist(Long toolId, List<String> roles);

    /** 停用/启用工具（0正常 1停用） */
    void updateStatus(Long toolId, Integer status);

    /** 调用审计分页查询 */
    PageResult<AiToolInvokeVO> pageInvokes(AiToolInvokeQuery query);
}
