package com.pivotos.ai.service;

import com.pivotos.ai.domain.entity.AiTool;
import com.pivotos.common.api.context.LoginUser;

import java.util.List;

/**
 * AI 工具权限守卫服务（S98 A2）
 *
 * <p>GuardedToolCallback 每次调用前的「停用检查 → 角色白名单」判定入口，
 * 工具快照（元数据 + 白名单）进程内缓存，白名单变更接口负责失效刷新。
 */
public interface AiToolGuardService {

    /** 工具权限快照（元数据 + 角色白名单） */
    record ToolSnapshot(AiTool tool, List<String> roleCodes) {
    }

    /**
     * 按工具名取权限快照（缓存优先）
     *
     * @param toolName 工具名（@Tool name）
     * @return 快照；工具未注册返回 null
     */
    ToolSnapshot snapshot(String toolName);

    /**
     * 校验调用人能否调用该工具，不允许时抛 ToolForbiddenException
     *
     * <p>规则：工具停用一律拒绝；白名单为空登录即可调用；
     * 白名单非空须命中 '*' 通配或调用人角色之一。
     *
     * @param snapshot  工具权限快照
     * @param loginUser 调用人（可为 null，null 一律拒绝）
     */
    void checkAllowed(ToolSnapshot snapshot, LoginUser loginUser);

    /** 失效指定工具缓存（白名单/状态变更后调用） */
    void invalidate(String toolName);

    /** 全量失效缓存 */
    void invalidateAll();
}
