package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.common.core.annotation.DataScope;
import com.pivotos.system.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/** 用户 Mapper - 启用数据权限过滤 */
@Mapper
@DataScope(deptColumn = "dept_id", userColumn = "id")
public interface SysUserMapper extends BaseMapper<SysUser> {
}
