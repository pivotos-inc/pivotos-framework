// SysDeptMapper.java
package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysDept;
import org.apache.ibatis.annotations.Mapper;

/** 部门 Mapper */
@Mapper
public interface SysDeptMapper extends BaseMapper<SysDept> {
}
