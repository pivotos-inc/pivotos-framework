package com.pivotos.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.message.domain.entity.MsgSendLog;
import org.apache.ibatis.annotations.Mapper;

/** 消息发送日志 Mapper */
@Mapper
public interface MsgSendLogMapper extends BaseMapper<MsgSendLog> {
}
