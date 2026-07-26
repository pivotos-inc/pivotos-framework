// SysRoleMenuMapper.java
package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysRoleMenu;
import org.apache.ibatis.annotations.Mapper;

/** 角色-菜单关联 Mapper */
@Mapper
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenu> {
}
