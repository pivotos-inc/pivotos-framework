package com.pivotos.system.convert;

import com.pivotos.system.domain.entity.SysLoginLog;
import com.pivotos.system.domain.vo.LoginLogVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 登录日志对象转换 */
@Mapper(componentModel = "spring")
public interface LoginLogConvert {

    LoginLogVO toVo(SysLoginLog source);

    List<LoginLogVO> toVoList(List<SysLoginLog> source);
}
