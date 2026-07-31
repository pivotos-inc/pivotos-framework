package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysLoginLog;
import org.apache.ibatis.annotations.Mapper;

/** 登录日志 Mapper */
@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {
}
