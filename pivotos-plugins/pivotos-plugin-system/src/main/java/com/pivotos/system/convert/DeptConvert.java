package com.pivotos.system.convert;

import com.pivotos.system.api.dto.DeptDTO;
import com.pivotos.system.domain.dto.DeptSaveRequest;
import com.pivotos.system.domain.entity.SysDept;
import com.pivotos.system.domain.vo.DeptVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 部门对象转换 */
@Mapper(componentModel = "spring")
public interface DeptConvert {

    DeptVO toVo(SysDept source);

    List<DeptVO> toVoList(List<SysDept> source);

    SysDept toEntity(DeptSaveRequest source);

    DeptDTO toDto(SysDept source);

    List<DeptDTO> toDtoList(List<SysDept> source);
}
