package com.pivotos.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.RoleQuery;
import com.pivotos.system.domain.dto.RoleSaveRequest;
import com.pivotos.system.domain.entity.SysRole;
import com.pivotos.system.domain.vo.RoleVO;

import java.util.List;

/** 角色服务 */
public interface RoleService extends IService<SysRole> {

    /** 分页查询角色 */
    PageResult<RoleVO> pageRoles(RoleQuery query);

    /** 查询全部正常状态角色（下拉选项用） */
    List<RoleVO> listAllEnabled();

    /** 查询角色详情 */
    RoleVO getRole(Long roleId);

    /** 查询角色已授权的菜单ID集合（编辑回显用） */
    List<Long> listMenuIds(Long roleId);

    /** 新增角色（含菜单授权），返回角色ID */
    Long createRole(RoleSaveRequest request);

    /** 修改角色（含菜单授权重建） */
    void updateRole(RoleSaveRequest request);

    /** 删除角色（已分配用户则拒绝） */
    void deleteRole(Long roleId);

    /** 查询用户拥有的角色编码集合 */
    List<String> listRoleCodesByUserId(Long userId);

    /** 查询用户拥有的角色实体集合 */
    List<SysRole> listRolesByUserId(Long userId);

    /** 是否超级管理员（持有 super_admin 角色） */
    boolean isSuperAdmin(Long userId);
}
