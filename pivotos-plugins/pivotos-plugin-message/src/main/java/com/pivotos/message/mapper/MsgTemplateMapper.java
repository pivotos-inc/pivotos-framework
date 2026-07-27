package com.pivotos.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.message.domain.entity.MsgTemplate;
import org.apache.ibatis.annotations.Mapper;

/** 消息模板 Mapper */
@Mapper
public interface MsgTemplateMapper extends BaseMapper<MsgTemplate> {
}
