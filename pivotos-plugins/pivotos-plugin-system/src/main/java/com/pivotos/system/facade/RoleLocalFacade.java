package com.pivotos.system.facade;

import com.pivotos.system.api.dto.RoleDTO;
import com.pivotos.system.api.facade.IRoleFacade;
import com.pivotos.system.convert.RoleConvert;
import com.pivotos.system.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 角色门面本地实现 */
@Component
@RequiredArgsConstructor
public class RoleLocalFacade implements IRoleFacade {

    private final RoleService roleService;
    private final RoleConvert roleConvert;

    @Override
    public List<RoleDTO> listByUserId(Long userId) {
        return roleConvert.toDtoList(roleService.listRolesByUserId(userId));
    }

    @Override
    public List<String> listRoleCodesByUserId(Long userId) {
        return roleService.listRoleCodesByUserId(userId);
    }
}
