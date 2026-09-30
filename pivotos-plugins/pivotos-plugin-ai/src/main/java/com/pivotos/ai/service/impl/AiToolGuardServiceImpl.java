package com.pivotos.ai.service.impl;

import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.ai.domain.entity.AiTool;
import com.pivotos.ai.domain.entity.AiToolRole;
import com.pivotos.ai.mapper.AiToolMapper;
import com.pivotos.ai.mapper.AiToolRoleMapper;
import com.pivotos.ai.service.AiToolGuardService;
import com.pivotos.ai.tool.ToolForbiddenException;
import com.pivotos.ai.tool.ToolGuardSignal;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.auth.account.StpAppUtil;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.auth.account.StpWxMiniUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 工具权限守卫服务实现（S98 A2）
 *
 * <p>快照缓存口径：进程内 ConcurrentHashMap，未注册工具缓存 NULL_SNAPSHOT 哨兵
 * 避免穿透；白名单/状态变更接口显式失效。单体形态无多实例一致性问题，
 * 后续集群化再换 Redis 缓存（口径记录于 S98 收尾文档）。
 */
@Service
@RequiredArgsConstructor
public class AiToolGuardServiceImpl implements AiToolGuardService {

    private static final Logger log = LoggerFactory.getLogger(AiToolGuardServiceImpl.class);

    /** 白名单通配角色码：等价「登录用户皆可调用」但保留显式登记痕迹 */
    private static final String WILDCARD_ROLE = "*";

    /** 未注册工具的哨兵快照（区分「未查过」与「查过不存在」） */
    private static final ToolSnapshot NULL_SNAPSHOT = new ToolSnapshot(null, List.of());

    private final AiToolMapper aiToolMapper;
    private final AiToolRoleMapper aiToolRoleMapper;

    /** 工具名 → 权限快照缓存 */
    private final Map<String, ToolSnapshot> snapshotCache = new ConcurrentHashMap<>();

    @Override
    public ToolSnapshot snapshot(String toolName) {
        ToolSnapshot cached = snapshotCache.get(toolName);
        if (cached != null) {
            return cached == NULL_SNAPSHOT ? null : cached;
        }
        AiTool tool = aiToolMapper.selectOne(Wrappers.<AiTool>lambdaQuery()
                .select(AiTool::getId, AiTool::getToolName, AiTool::getToolType,
                        AiTool::getConfirmRequired, AiTool::getStatus)
                .eq(AiTool::getToolName, toolName));
        if (tool == null) {
            snapshotCache.put(toolName, NULL_SNAPSHOT);
            return null;
        }
        List<String> roleCodes = aiToolRoleMapper.selectList(Wrappers.<AiToolRole>lambdaQuery()
                        .select(AiToolRole::getRoleCode)
                        .eq(AiToolRole::getToolId, tool.getId()))
                .stream().map(AiToolRole::getRoleCode).toList();
        ToolSnapshot snapshot = new ToolSnapshot(tool, roleCodes);
        snapshotCache.put(toolName, snapshot);
        return snapshot;
    }

    @Override
    public void checkAllowed(ToolSnapshot snapshot, LoginUser loginUser) {
        AiTool tool = snapshot.tool();
        if (tool.getStatus() != null && tool.getStatus() == 1) {
            throw new ToolForbiddenException(ToolGuardSignal.DISABLED + tool.getToolName());
        }
        if (loginUser == null || loginUser.getUserId() == null) {
            throw new ToolForbiddenException(ToolGuardSignal.LOGIN_REQUIRED + tool.getToolName());
        }
        List<String> whitelist = snapshot.roleCodes();
        if (whitelist.isEmpty() || whitelist.contains(WILDCARD_ROLE)) {
            return;
        }
        List<String> userRoles = resolveRoles(loginUser);
        boolean hit = userRoles.stream().anyMatch(whitelist::contains);
        if (!hit) {
            log.warn("[PivotOS] AI 工具越权调用被拒：tool={} userId={} roles={} whitelist={}",
                    tool.getToolName(), loginUser.getUserId(), userRoles, whitelist);
            throw new ToolForbiddenException(ToolGuardSignal.FORBIDDEN + tool.getToolName());
        }
    }

    @Override
    public void invalidate(String toolName) {
        snapshotCache.remove(toolName);
    }

    @Override
    public void invalidateAll() {
        snapshotCache.clear();
    }

    /**
     * 按账号体系取调用人角色列表（Sa-Token 多账号 StpLogic 分体系查询）
     */
    private List<String> resolveRoles(LoginUser loginUser) {
        StpLogic logic = resolveLogic(loginUser.getAccountType());
        if (logic == null) {
            return List.of();
        }
        try {
            return logic.getRoleList(loginUser.getUserId());
        } catch (Exception e) {
            log.warn("[PivotOS] AI 工具角色解析失败（按无角色口径）：userId={} err={}",
                    loginUser.getUserId(), e.getMessage());
            return List.of();
        }
    }

    private StpLogic resolveLogic(String accountType) {
        if (StpSysUtil.TYPE.equals(accountType)) {
            return StpSysUtil.STP;
        }
        if (StpAppUtil.TYPE.equals(accountType)) {
            return StpAppUtil.STP;
        }
        if (StpWxMiniUtil.TYPE.equals(accountType)) {
            return StpWxMiniUtil.STP;
        }
        return null;
    }
}
