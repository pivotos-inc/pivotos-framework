// SysUserRoleMapper.java
package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

/** 用户-角色关联 Mapper */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
