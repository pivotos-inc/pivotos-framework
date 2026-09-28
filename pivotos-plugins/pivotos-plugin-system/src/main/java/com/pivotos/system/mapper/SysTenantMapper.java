package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysTenant;
import org.apache.ibatis.annotations.Mapper;

/** 租户 Mapper */
@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {
}
