package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.constant.SystemConstants;
import com.pivotos.system.convert.RoleConvert;
import com.pivotos.system.domain.dto.RoleQuery;
import com.pivotos.system.domain.dto.RoleSaveRequest;
import com.pivotos.system.domain.entity.SysRole;
import com.pivotos.system.domain.entity.SysRoleMenu;
import com.pivotos.system.domain.entity.SysUserRole;
import com.pivotos.system.domain.vo.RoleVO;
import com.pivotos.system.mapper.SysRoleMapper;
import com.pivotos.system.mapper.SysRoleMenuMapper;
import com.pivotos.system.mapper.SysUserRoleMapper;
import com.pivotos.system.service.RoleService;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** 角色服务实现 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements RoleService {

    private final RoleConvert roleConvert;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;

    @Override
    public PageResult<RoleVO> pageRoles(RoleQuery query) {
        Page<SysRole> page = page(PageUtils.toMpPage(query), Wrappers.<SysRole>lambdaQuery()
                .like(StringUtils.hasText(query.getRoleName()), SysRole::getRoleName, query.getRoleName())
                .like(StringUtils.hasText(query.getRoleCode()), SysRole::getRoleCode, query.getRoleCode())
                .eq(query.getStatus() != null, SysRole::getStatus, query.getStatus())
                .orderByAsc(SysRole::getSort));
        return PageUtils.toPageResult(page, roleConvert.toVoList(page.getRecords()));
    }

    @Override
    public List<RoleVO> listAllEnabled() {
        return roleConvert.toVoList(list(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getStatus, CommonStatusEnum.ENABLED.getValue())
                .orderByAsc(SysRole::getSort)));
    }

    @Override
    public RoleVO getRole(Long roleId) {
        return roleConvert.toVo(requireRole(roleId));
    }

    @Override
    public List<Long> listMenuIds(Long roleId) {
        return roleMenuMapper.selectList(Wrappers.<SysRoleMenu>lambdaQuery()
                        .eq(SysRoleMenu::getRoleId, roleId))
                .stream().map(SysRoleMenu::getMenuId).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRole(RoleSaveRequest request) {
        checkRoleCodeUnique(request.getRoleCode(), null);
        SysRole entity = roleConvert.toEntity(request);
        entity.setId(null);
        save(entity);
        rebuildRoleMenus(entity.getId(), request.getMenuIds());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(RoleSaveRequest request) {
        requireRole(request.getId());
        checkSuperAdminRole(request.getId());
        checkRoleCodeUnique(request.getRoleCode(), request.getId());
        updateById(roleConvert.toEntity(request));
        rebuildRoleMenus(request.getId(), request.getMenuIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long roleId) {
        requireRole(roleId);
        checkSuperAdminRole(roleId);
        long assigned = userRoleMapper.selectCount(
                Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getRoleId, roleId));
        if (assigned > 0) {
            throw new ServiceException(SystemErrorCode.ROLE_ASSIGNED);
        }
        removeById(roleId);
        roleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getRoleId, roleId));
    }

    @Override
    public List<String> listRoleCodesByUserId(Long userId) {
        return listRolesByUserId(userId).stream().map(SysRole::getRoleCode).toList();
    }

    @Override
    public List<SysRole> listRolesByUserId(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return list(Wrappers.<SysRole>lambdaQuery()
                .in(SysRole::getId, roleIds)
                .eq(SysRole::getStatus, CommonStatusEnum.ENABLED.getValue()));
    }

    @Override
    public boolean isSuperAdmin(Long userId) {
        return listRoleCodesByUserId(userId).contains(SystemConstants.SUPER_ADMIN_ROLE);
    }

    private SysRole requireRole(Long roleId) {
        SysRole role = getById(roleId);
        if (role == null) {
            throw new ServiceException(SystemErrorCode.ROLE_NOT_FOUND);
        }
        return role;
    }

    private void checkSuperAdminRole(Long roleId) {
        if (com.pivotos.common.core.constant.CommonConstants.SUPER_ADMIN_ID.equals(roleId)) {
            throw new ServiceException(SystemErrorCode.SUPER_ADMIN_FORBIDDEN);
        }
    }

    private void checkRoleCodeUnique(String roleCode, Long excludeId) {
        long count = count(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, roleCode)
                .ne(excludeId != null, SysRole::getId, excludeId));
        if (count > 0) {
            throw new ServiceException(SystemErrorCode.ROLE_CODE_EXISTS);
        }
    }

    /** 重建角色-菜单授权（先删后插） */
    private void rebuildRoleMenus(Long roleId, List<Long> menuIds) {
        roleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getRoleId, roleId));
        if (menuIds != null && !menuIds.isEmpty()) {
            menuIds.forEach(menuId -> roleMenuMapper.insert(new SysRoleMenu(roleId, menuId)));
        }
    }
}
