package com.pivotos.system.convert;

import com.pivotos.system.domain.dto.MenuSaveRequest;
import com.pivotos.system.domain.entity.SysMenu;
import com.pivotos.system.domain.vo.MenuVO;
import org.mapstruct.Mapper;

import java.util.List;

/** 菜单对象转换 */
@Mapper
public interface MenuConvert {

    MenuVO toVo(SysMenu source);

    List<MenuVO> toVoList(List<SysMenu> source);

    SysMenu toEntity(MenuSaveRequest source);
}
