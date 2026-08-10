package com.pivotos.system.convert;

import com.pivotos.system.domain.dto.NoticeSaveRequest;
import com.pivotos.system.domain.entity.SysNotice;
import com.pivotos.system.domain.vo.NoticeVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 通知公告对象转换 */
@Mapper(componentModel = "spring")
public interface NoticeConvert {

    NoticeVO toVo(SysNotice source);

    List<NoticeVO> toVoList(List<SysNotice> source);

    SysNotice toEntity(NoticeSaveRequest source);
}
