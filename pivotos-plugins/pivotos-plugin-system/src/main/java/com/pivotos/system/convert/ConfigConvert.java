package com.pivotos.system.convert;

import com.pivotos.system.domain.dto.ConfigSaveRequest;
import com.pivotos.system.domain.entity.SysConfig;
import com.pivotos.system.domain.vo.ConfigVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 参数配置对象转换 */
@Mapper(componentModel = "spring")
public interface ConfigConvert {

    ConfigVO toVo(SysConfig source);

    List<ConfigVO> toVoList(List<SysConfig> source);

    SysConfig toEntity(ConfigSaveRequest source);
}
