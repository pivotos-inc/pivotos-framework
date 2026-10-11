package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiChatMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** AI 对话消息 Mapper */
@Mapper
public interface AiChatMessageMapper extends BaseMapper<AiChatMessage> {

    /**
     * 物理删除（仅供留存清理使用）。
     * <p>
     * {@code BaseDO#deleted} 带 {@code @TableLogic}，{@code BaseMapper#delete*} 只置
     * {@code deleted=1} 不回收空间，故留存清理必须显式 {@code DELETE}。
     * <p>
     * <b>本表沿用默认租户过滤（不加 @InterceptorIgnore）</b>：会话消息是租户内的用户数据，
     * 与 {@code AiUsageMapper} 的「平台跨租户视角」不是一类。调用方必须逐租户执行
     * （{@code RetentionCleanupTemplate#runPerTenant}）。
     *
     * @param ids 待删主键
     * @return 实际删除行数
     */
    @Delete("<script>DELETE FROM ai_chat_message WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int physicalDeleteByIds(@Param("ids") List<Long> ids);
}
