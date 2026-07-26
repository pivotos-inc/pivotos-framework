package com.pivotos.system.convert;

import com.pivotos.system.domain.dto.DictDataSaveRequest;
import com.pivotos.system.domain.entity.SysDictData;
import com.pivotos.system.domain.vo.DictDataVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 字典数据对象转换 */
@Mapper
public interface DictDataConvert {

    DictDataVO toVo(SysDictData source);

    List<DictDataVO> toVoList(List<SysDictData> source);

    SysDictData toEntity(DictDataSaveRequest source);
}
