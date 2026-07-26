package com.pivotos.system.convert;

import com.pivotos.system.domain.dto.RoleSaveRequest;
import com.pivotos.system.domain.entity.SysRole;
import com.pivotos.system.domain.vo.RoleVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 角色对象转换 */
@Mapper
public interface RoleConvert {

    RoleVO toVo(SysRole source);

    List<RoleVO> toVoList(List<SysRole> source);

    SysRole toEntity(RoleSaveRequest source);
}
