package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysJob;
import org.apache.ibatis.annotations.Mapper;

/** 定时任务管理 Mapper */
@Mapper
public interface SysJobMapper extends BaseMapper<SysJob> {
}
