package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiToolRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AI 工具角色白名单 Mapper（S98 A2） */
@Mapper
public interface AiToolRoleMapper extends BaseMapper<AiToolRole> {

    /**
     * 物理删除工具的全部白名单记录（含历史逻辑删除行）。
     *
     * <p>uk_tool_role(tool_id, role_code) 不含 deleted：逻辑删除后再加回同名角色会撞唯一键，
     * 全量替换场景必须物理删除（口径同 AiKbChunk：易变关联行不做逻辑删除）。
     */
    @Delete("DELETE FROM ai_tool_role WHERE tool_id = #{toolId}")
    int physicalDeleteByToolId(@Param("toolId") Long toolId);
}
