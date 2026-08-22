package com.pivotos.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.domain.dto.AiToolInvokeQuery;
import com.pivotos.ai.domain.dto.AiToolQuery;
import com.pivotos.ai.domain.entity.AiTool;
import com.pivotos.ai.domain.entity.AiToolInvoke;
import com.pivotos.ai.domain.entity.AiToolRole;
import com.pivotos.ai.domain.vo.AiToolInvokeVO;
import com.pivotos.ai.domain.vo.AiToolVO;
import com.pivotos.ai.enums.ToolType;
import com.pivotos.ai.mapper.AiToolInvokeMapper;
import com.pivotos.ai.mapper.AiToolMapper;
import com.pivotos.ai.mapper.AiToolRoleMapper;
import com.pivotos.ai.service.AiToolGuardService;
import com.pivotos.ai.service.AiToolService;
import com.pivotos.ai.tool.AiToolMeta;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 工具注册管理服务实现（S98 A2）
 *
 * <p>同步口径：以容器内全部 ToolCallbackProvider 汇聚结果为「活工具」基准，
 * @Tool 方法上的 @AiToolMeta 声明提供类型/二次确认元数据；运行期白名单/状态
 * 修改不被同步覆盖（同步只刷新描述与代码侧声明）。
 */
@Service
@RequiredArgsConstructor
public class AiToolServiceImpl implements AiToolService {

    private static final Logger log = LoggerFactory.getLogger(AiToolServiceImpl.class);

    /** 工具来源：@Tool 扫描自动注册 */
    private static final String SOURCE_REGISTER = "register";

    /** ai_tool.description 截断长度（VARCHAR(500)） */
    private static final int DESC_MAX_LEN = 500;

    private final AiToolMapper aiToolMapper;
    private final AiToolRoleMapper aiToolRoleMapper;
    private final AiToolInvokeMapper aiToolInvokeMapper;
    private final AiToolGuardService guardService;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProviders;
    private final ApplicationContext applicationContext;

    @Override
    public void syncRegisteredTools() {
        Map<String, String> liveTools = collectLiveTools();
        Map<String, AiToolMeta> metaMap = scanToolMeta();
        int inserted = 0;
        int updated = 0;
        for (Map.Entry<String, String> entry : liveTools.entrySet()) {
            String toolName = entry.getKey();
            AiTool existing = aiToolMapper.selectOne(Wrappers.<AiTool>lambdaQuery()
                    .eq(AiTool::getToolName, toolName));
            AiToolMeta meta = metaMap.get(toolName);
            if (existing == null) {
                AiTool tool = new AiTool();
                tool.setToolName(toolName);
                tool.setDisplayName(toolName);
                tool.setDescription(truncate(entry.getValue(), DESC_MAX_LEN));
                tool.setToolType(meta != null ? meta.type().getCode() : ToolType.READ.getCode());
                tool.setConfirmRequired(meta != null ? (meta.confirmRequired() ? 1 : 0) : 0);
                tool.setStatus(0);
                tool.setSource(SOURCE_REGISTER);
                aiToolMapper.insert(tool);
                inserted++;
            } else {
                existing.setDescription(truncate(entry.getValue(), DESC_MAX_LEN));
                if (meta != null) {
                    existing.setToolType(meta.type().getCode());
                    existing.setConfirmRequired(meta.confirmRequired() ? 1 : 0);
                }
                aiToolMapper.updateById(existing);
                updated++;
            }
        }
        // 代码中已移除的 register 来源工具逻辑删除（白名单/审计历史保留）
        int removed = 0;
        List<AiTool> registered = aiToolMapper.selectList(Wrappers.<AiTool>lambdaQuery()
                .select(AiTool::getId, AiTool::getToolName)
                .eq(AiTool::getSource, SOURCE_REGISTER));
        for (AiTool tool : registered) {
            if (!liveTools.containsKey(tool.getToolName())) {
                aiToolMapper.deleteById(tool.getId());
                removed++;
            }
        }
        guardService.invalidateAll();
        log.info("[PivotOS] AI 工具注册表同步完成：活工具 {} 个（新增 {} / 刷新 {} / 移除 {}）",
                liveTools.size(), inserted, updated, removed);
    }

    @Override
    public PageResult<AiToolVO> pageTools(AiToolQuery query) {
        Page<AiTool> page = aiToolMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<AiTool>lambdaQuery()
                        .like(StringUtils.hasText(query.getToolName()), AiTool::getToolName, query.getToolName())
                        .eq(StringUtils.hasText(query.getToolType()), AiTool::getToolType, query.getToolType())
                        .eq(query.getStatus() != null, AiTool::getStatus, query.getStatus())
                        .orderByAsc(AiTool::getToolName));
        List<AiTool> records = page.getRecords();
        Map<Long, List<String>> rolesByToolId = loadRolesByToolIds(
                records.stream().map(AiTool::getId).toList());
        List<AiToolVO> list = records.stream().map(tool -> toVO(tool,
                rolesByToolId.getOrDefault(tool.getId(), List.of()))).toList();
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRoleWhitelist(Long toolId, List<String> roles) {
        AiTool tool = aiToolMapper.selectById(toolId);
        if (tool == null) {
            throw new ServiceException(AiErrorCode.AI_TOOL_NOT_FOUND);
        }
        List<String> distinct = roles == null ? List.of() : roles.stream()
                .filter(StringUtils::hasText).map(String::trim).distinct().toList();
        // 全量替换：删旧插新（口径同代码生成器子表更新语义）
        aiToolRoleMapper.delete(Wrappers.<AiToolRole>lambdaQuery().eq(AiToolRole::getToolId, toolId));
        if (!distinct.isEmpty()) {
            List<AiToolRole> entities = distinct.stream().map(roleCode -> {
                AiToolRole role = new AiToolRole();
                role.setToolId(toolId);
                role.setRoleCode(roleCode);
                return role;
            }).toList();
            Db.saveBatch(entities);
        }
        guardService.invalidate(tool.getToolName());
        log.info("[PivotOS] AI 工具白名单更新：tool={} roles={}", tool.getToolName(), distinct);
    }

    @Override
    public void updateStatus(Long toolId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new ServiceException(AiErrorCode.AI_TOOL_STATUS_INVALID);
        }
        AiTool tool = aiToolMapper.selectById(toolId);
        if (tool == null) {
            throw new ServiceException(AiErrorCode.AI_TOOL_NOT_FOUND);
        }
        AiTool update = new AiTool();
        update.setId(toolId);
        update.setStatus(status);
        aiToolMapper.updateById(update);
        guardService.invalidate(tool.getToolName());
        log.info("[PivotOS] AI 工具状态更新：tool={} status={}", tool.getToolName(), status);
    }

    @Override
    public PageResult<AiToolInvokeVO> pageInvokes(AiToolInvokeQuery query) {
        Page<AiToolInvoke> page = aiToolInvokeMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<AiToolInvoke>lambdaQuery()
                        .like(StringUtils.hasText(query.getToolName()), AiToolInvoke::getToolName, query.getToolName())
                        .eq(StringUtils.hasText(query.getInvokeStatus()), AiToolInvoke::getInvokeStatus, query.getInvokeStatus())
                        .eq(query.getUserId() != null, AiToolInvoke::getUserId, query.getUserId())
                        .orderByDesc(AiToolInvoke::getCreateTime));
        List<AiToolInvokeVO> list = page.getRecords().stream().map(this::toInvokeVO).toList();
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 汇聚容器内全部 ToolCallbackProvider 的活工具（名称 → 描述）
     */
    private Map<String, String> collectLiveTools() {
        Map<String, String> live = new LinkedHashMap<>();
        for (ToolCallbackProvider provider : toolCallbackProviders) {
            for (org.springframework.ai.tool.ToolCallback callback : provider.getToolCallbacks()) {
                org.springframework.ai.tool.definition.ToolDefinition definition = callback.getToolDefinition();
                live.putIfAbsent(definition.name(), definition.description());
            }
        }
        return live;
    }

    /**
     * 反射扫描容器 Bean 的 @Tool 方法，收集 @AiToolMeta 声明（工具名 → 元数据）
     */
    private Map<String, AiToolMeta> scanToolMeta() {
        Map<String, AiToolMeta> metaMap = new LinkedHashMap<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type = applicationContext.getType(beanName);
            if (type == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);
            for (Method method : userClass.getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                String toolName = StringUtils.hasText(tool.name()) ? tool.name() : method.getName();
                AiToolMeta meta = method.getAnnotation(AiToolMeta.class);
                if (meta != null) {
                    metaMap.put(toolName, meta);
                }
            }
        }
        return metaMap;
    }

    private Map<Long, List<String>> loadRolesByToolIds(List<Long> toolIds) {
        if (toolIds.isEmpty()) {
            return Map.of();
        }
        List<AiToolRole> roles = aiToolRoleMapper.selectList(Wrappers.<AiToolRole>lambdaQuery()
                .select(AiToolRole::getToolId, AiToolRole::getRoleCode)
                .in(AiToolRole::getToolId, new HashSet<>(toolIds)));
        return roles.stream().collect(Collectors.groupingBy(AiToolRole::getToolId,
                Collectors.mapping(AiToolRole::getRoleCode, Collectors.toList())));
    }

    private AiToolVO toVO(AiTool tool, List<String> roles) {
        AiToolVO vo = new AiToolVO();
        vo.setId(tool.getId());
        vo.setToolName(tool.getToolName());
        vo.setDisplayName(tool.getDisplayName());
        vo.setDescription(tool.getDescription());
        vo.setToolType(tool.getToolType());
        vo.setConfirmRequired(tool.getConfirmRequired());
        vo.setStatus(tool.getStatus());
        vo.setSource(tool.getSource());
        vo.setCreateTime(tool.getCreateTime());
        vo.setRoles(new ArrayList<>(roles));
        return vo;
    }

    private AiToolInvokeVO toInvokeVO(AiToolInvoke invoke) {
        AiToolInvokeVO vo = new AiToolInvokeVO();
        vo.setId(invoke.getId());
        vo.setToolName(invoke.getToolName());
        vo.setUserId(invoke.getUserId());
        vo.setArgsSummary(invoke.getArgsSummary());
        vo.setInvokeStatus(invoke.getInvokeStatus());
        vo.setErrorMsg(invoke.getErrorMsg());
        vo.setCostMs(invoke.getCostMs());
        vo.setTraceId(invoke.getTraceId());
        vo.setCreateTime(invoke.getCreateTime());
        return vo;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
