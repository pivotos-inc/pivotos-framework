package com.pivotos.system.convert;

import com.pivotos.system.domain.dto.DictTypeSaveRequest;
import com.pivotos.system.domain.entity.SysDictType;
import com.pivotos.system.domain.vo.DictTypeVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 字典类型对象转换 */
@Mapper
public interface DictTypeConvert {

    DictTypeVO toVo(SysDictType source);

    List<DictTypeVO> toVoList(List<SysDictType> source);

    SysDictType toEntity(DictTypeSaveRequest source);
}
