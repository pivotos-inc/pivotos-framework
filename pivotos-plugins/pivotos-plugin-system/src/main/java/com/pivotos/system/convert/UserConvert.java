package com.pivotos.system.convert;

import com.pivotos.system.api.dto.UserDTO;
import com.pivotos.system.domain.dto.UserSaveRequest;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.vo.UserVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 用户对象转换 */
@Mapper
public interface UserConvert {

    UserVO toVo(SysUser source);

    List<UserVO> toVoList(List<SysUser> source);

    SysUser toEntity(UserSaveRequest source);

    UserDTO toDto(SysUser source);

    List<UserDTO> toDtoList(List<SysUser> source);
}
