package com.pivotos.system.convert;

import com.pivotos.system.domain.dto.TenantPackageSaveRequest;
import com.pivotos.system.domain.entity.SysTenantPackage;
import com.pivotos.system.domain.vo.TenantPackageVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/** 租户套餐对象转换（menuIds 为 String(JSON)↔List<Long> 类型冲突，服务层手工处理） */
@Mapper(componentModel = "spring")
public interface TenantPackageConvert {

    @Mapping(target = "menuIds", ignore = true)
    TenantPackageVO toVo(SysTenantPackage source);

    List<TenantPackageVO> toVoList(List<SysTenantPackage> source);

    @Mapping(target = "menuIds", ignore = true)
    SysTenantPackage toEntity(TenantPackageSaveRequest source);
}
