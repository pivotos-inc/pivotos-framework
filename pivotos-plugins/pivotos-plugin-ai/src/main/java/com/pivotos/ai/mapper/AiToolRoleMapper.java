package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiToolRole;
import org.apache.ibatis.annotations.Mapper;

/** AI 工具角色白名单 Mapper（S98 A2） */
@Mapper
public interface AiToolRoleMapper extends BaseMapper<AiToolRole> {
}
