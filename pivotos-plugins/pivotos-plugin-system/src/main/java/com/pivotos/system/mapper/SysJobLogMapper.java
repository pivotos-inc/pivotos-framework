package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysJobLog;
import org.apache.ibatis.annotations.Mapper;

/** 定时任务执行记录 Mapper */
@Mapper
public interface SysJobLogMapper extends BaseMapper<SysJobLog> {
}
