package com.pivotos.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.message.domain.entity.MsgSendLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 消息发送日志 Mapper */
@Mapper
public interface MsgSendLogMapper extends BaseMapper<MsgSendLog> {

    /**
     * 物理删除（仅供留存清理使用）。理由与前置条件同
     * {@code MsgMessageMapper#physicalDeleteByIds}（@TableLogic 不回收空间；本表有 tenant_id，
     * 必须在已绑定租户上下文的作用域内调用）。
     *
     * @param ids 待删主键
     * @return 实际删除行数
     */
    @Delete("<script>DELETE FROM msg_send_log WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int physicalDeleteByIds(@Param("ids") List<Long> ids);
}
