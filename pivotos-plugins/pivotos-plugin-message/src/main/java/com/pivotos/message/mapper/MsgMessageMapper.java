package com.pivotos.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.message.domain.entity.MsgMessage;
import org.apache.ibatis.annotations.Mapper;

/** 消息主表 Mapper */
@Mapper
public interface MsgMessageMapper extends BaseMapper<MsgMessage> {
}
