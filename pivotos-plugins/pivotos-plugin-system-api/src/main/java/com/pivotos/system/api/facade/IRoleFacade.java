package com.pivotos.system.api.facade;

import com.pivotos.system.api.dto.RoleDTO;

import java.util.List;

/**
 * 角色门面契约
 */
public interface IRoleFacade {

    /**
     * 查询用户拥有的角色列表
     *
     * @param userId 用户 ID
     * @return 角色列表，无角色返回空列表
     */
    List<RoleDTO> listByUserId(Long userId);

    /**
     * 查询用户拥有的角色编码列表
     *
     * @param userId 用户 ID
     * @return 角色编码列表（如 ["super_admin"]），无角色返回空列表
     */
    List<String> listRoleCodesByUserId(Long userId);
}
