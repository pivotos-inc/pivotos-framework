package com.pivotos.message.convert;

import com.pivotos.message.domain.dto.TemplateSaveRequest;
import com.pivotos.message.domain.entity.MsgTemplate;
import com.pivotos.message.domain.vo.TemplateVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 消息模板对象转换 */
@Mapper(componentModel = "spring")
public interface TemplateConvert {

    TemplateVO toVo(MsgTemplate source);

    List<TemplateVO> toVoList(List<MsgTemplate> source);

    MsgTemplate toEntity(TemplateSaveRequest source);
}
