package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/** 用户 Mapper */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
