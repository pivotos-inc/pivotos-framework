package com.pivotos.system.datascope;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.datascope.context.DataScopeContext;
import com.pivotos.starter.datascope.enums.DataScopeEnum;
import com.pivotos.system.constant.SystemConstants;
import com.pivotos.system.domain.entity.SysRole;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.entity.SysUserRole;
import com.pivotos.system.mapper.SysRoleMapper;
import com.pivotos.system.mapper.SysUserMapper;
import com.pivotos.system.mapper.SysUserRoleMapper;
import com.pivotos.system.service.DeptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据权限解析器。
 * <p>
 * 根据当前登录用户的角色数据范围配置，解析出 DataScopeInfo。
 * 实际 ScopedValue 绑定由 DataScopeBindingFilter 完成。
 *
 * @author PivotOS Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataScopeHelper {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final DeptService deptService;

    /**
     * 解析当前用户的数据权限，返回 DataScopeInfo。
     * ScopedValue 绑定由调用方（DataScopeBindingFilter）负责。
     *
     * @return 数据权限信息；未登录返回 null
     */
    public DataScopeContext.DataScopeInfo resolve() {
        LoginUser loginUser = LoginContext.get();
        if (loginUser == null) {
            log.debug("[PivotOS] 未登录用户，跳过数据权限解析");
            return null;
        }

        Long userId = loginUser.getUserId();
        if (userId == null) {
            return null;
        }

        // 1. 查询用户角色
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();

        if (roleIds.isEmpty()) {
            log.debug("[PivotOS] 用户 {} 无角色，默认全部数据权限", userId);
            return allScope(userId);
        }

        List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
        boolean isSuperAdmin = roles.stream()
                .anyMatch(r -> SystemConstants.SUPER_ADMIN_ROLE.equals(r.getRoleCode()));

        if (isSuperAdmin) {
            log.debug("[PivotOS] 超级管理员 {}，跳过数据权限过滤", userId);
            return DataScopeContext.DataScopeInfo.skip(userId);
        }

        // 2. 查询用户部门
        SysUser sysUser = sysUserMapper.selectById(userId);
        Long deptId = sysUser != null ? sysUser.getDeptId() : null;

        // 3. 解析数据范围（多角色取最高权限：ALL > DEPT > DEPT_AND_BELOW > CUSTOM > SELF）
        DataScopeEnum bestScope = DataScopeEnum.SELF;
        String customDeptIds = null;

        for (SysRole role : roles) {
            DataScopeEnum roleScope = DataScopeEnum.fromCode(role.getDataScope());
            if (roleScope == DataScopeEnum.ALL) {
                bestScope = DataScopeEnum.ALL;
                break;
            }
            if (roleScope == DataScopeEnum.DEPT && bestScope.ordinal() > DataScopeEnum.DEPT.ordinal()) {
                bestScope = DataScopeEnum.DEPT;
            }
            if (roleScope == DataScopeEnum.DEPT_AND_BELOW && bestScope.ordinal() > DataScopeEnum.DEPT_AND_BELOW.ordinal()) {
                bestScope = DataScopeEnum.DEPT_AND_BELOW;
            }
            if (roleScope == DataScopeEnum.CUSTOM) {
                if (StringUtils.hasText(role.getCustomDeptIds())) {
                    customDeptIds = role.getCustomDeptIds();
                }
                if (bestScope.ordinal() > DataScopeEnum.CUSTOM.ordinal()) {
                    bestScope = DataScopeEnum.CUSTOM;
                }
            }
        }

        // 4. 计算可见部门 ID
        Set<Long> visibleDeptIds = computeVisibleDeptIds(bestScope, deptId, customDeptIds);

        log.debug("[PivotOS] 用户 {} 数据权限：scope={} deptId={} visibleDeptIds={}",
                userId, bestScope.getDesc(), deptId, visibleDeptIds);

        return DataScopeContext.DataScopeInfo.of(bestScope, userId, deptId, visibleDeptIds);
    }

    private DataScopeContext.DataScopeInfo allScope(Long userId) {
        return DataScopeContext.DataScopeInfo.skip(userId);
    }

    private Set<Long> computeVisibleDeptIds(DataScopeEnum scope, Long deptId, String customDeptIds) {
        return switch (scope) {
            case ALL -> Set.of();
            case DEPT -> deptId != null ? Set.of(deptId) : Set.of();
            case DEPT_AND_BELOW -> {
                if (deptId == null) yield Set.of();
                Set<Long> ids = deptService.getSubtreeDeptIds(deptId);
                ids.add(deptId);
                yield ids;
            }
            case SELF -> Set.of();
            case CUSTOM -> parseCustomDeptIds(customDeptIds);
        };
    }

    private Set<Long> parseCustomDeptIds(String customDeptIds) {
        if (!StringUtils.hasText(customDeptIds)) {
            return Set.of();
        }
        return Arrays.stream(customDeptIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }
}
