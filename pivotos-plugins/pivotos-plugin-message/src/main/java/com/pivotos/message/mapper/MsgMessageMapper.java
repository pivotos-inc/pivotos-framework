package com.pivotos.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.message.domain.entity.MsgMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 消息主表 Mapper */
@Mapper
public interface MsgMessageMapper extends BaseMapper<MsgMessage> {

    /**
     * 物理删除（仅供留存清理使用）。
     * <p>
     * {@code BaseDO#deleted} 带 {@code @TableLogic}，{@code BaseMapper#delete*} 只置
     * {@code deleted=1} 不回收空间，故留存清理必须显式 {@code DELETE}。
     * <p>
     * <b>本表有 tenant_id 列 → 由租户行级过滤保护</b>：调用方必须在已绑定租户上下文的
     * 作用域内调用（{@code RetentionCleanupTemplate#runPerTenant} 负责绑定），
     * 且传入的主键本身就是带租户过滤查出来的，双重保险。
     *
     * @param ids 待删主键
     * @return 实际删除行数
     */
    @Delete("<script>DELETE FROM msg_message WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int physicalDeleteByIds(@Param("ids") List<Long> ids);
}
